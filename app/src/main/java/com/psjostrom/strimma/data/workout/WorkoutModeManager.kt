package com.psjostrom.strimma.data.workout

import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val MS_PER_HOUR = 3_600_000L
private const val TICK_INTERVAL_MS = 30_000L

/**
 * Single source of truth for workout-mode state and the threshold set every
 * downstream consumer should use.
 *
 * State machine (rules in priority order, see spec for full table):
 *   R1. manualSinceMs != null && now < manualExpiresAtMs       → On(MANUAL)
 *   R3. overrideUntilMs != null && now < overrideUntilMs       → Off
 *   R5. nextEvent != null && start <= now <= end               → On(CALENDAR)
 *   else                                                        → Off
 *
 * R2 (manual expired) and R4 (override expired) are handled by a separate
 * cleanup launch driven by the same ticker, so the state-derivation flow
 * stays a pure function of its inputs.
 */
@Singleton
class WorkoutModeManager @Inject constructor(
    private val settings: SettingsRepository,
    private val calendarSource: CalendarPollerSource,
    private val clock: Clock,
    private val scope: CoroutineScope
) {
    private val ticker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_INTERVAL_MS)
        }
    }

    val state: StateFlow<WorkoutMode> = combine(
        settings.manualWorkoutSinceMs,
        settings.manualOffOverrideUntilMs,
        settings.workoutModeMaxHours,
        calendarSource.nextEvent,
        ticker
    ) { manualSince, overrideUntil, maxHours, nextEvent, _ ->
        computeState(manualSince, overrideUntil, maxHours, nextEvent, clock.nowMs())
    }.stateIn(scope, SharingStarted.Eagerly, WorkoutMode.Off)

    /** Pure state derivation — no side effects, fully testable in isolation. */
    internal fun computeState(
        manualSince: Long?,
        overrideUntil: Long?,
        maxHours: Int,
        nextEvent: WorkoutEvent?,
        now: Long
    ): WorkoutMode {
        val manualExpiresAt = manualSince?.let { it + maxHours * MS_PER_HOUR }
        val isCalendarActive = nextEvent != null
            && now >= nextEvent.startTime
            && now <= nextEvent.endTime

        // R1: manual ON, not yet expired
        if (manualSince != null && manualExpiresAt != null && now < manualExpiresAt) {
            return WorkoutMode.On(
                source = WorkoutMode.On.Source.MANUAL,
                sinceMs = manualSince,
                expiresAtMs = manualExpiresAt
            )
        }
        // R3: manual OFF override active
        if (overrideUntil != null && now < overrideUntil) {
            return WorkoutMode.Off
        }
        // R5: calendar event currently active
        if (isCalendarActive && nextEvent != null) {
            return WorkoutMode.On(
                source = WorkoutMode.On.Source.CALENDAR,
                sinceMs = nextEvent.startTime,
                expiresAtMs = nextEvent.endTime
            )
        }
        return WorkoutMode.Off
    }

    /** Combined snapshot of all threshold settings. Builds via vararg combine. */
    private val thresholdSnapshot: Flow<ThresholdSnapshot> = combine(
        settings.bgLow,                    // [0]
        settings.bgHigh,                   // [1]
        settings.alertLow,                 // [2]
        settings.alertHigh,                // [3]
        settings.alertUrgentLow,           // [4]
        settings.alertUrgentHigh,          // [5]
        settings.workoutAlertLow,          // [6]
        settings.workoutAlertHigh,         // [7]
        settings.workoutAlertUrgentLow,    // [8]
        settings.workoutAlertUrgentHigh    // [9]
    ) { values: Array<Float> ->
        ThresholdSnapshot(
            bgLow = values[0],
            bgHigh = values[1],
            alertLow = values[2],
            alertHigh = values[3],
            alertUrgentLow = values[4],
            alertUrgentHigh = values[5],
            workoutLow = values[6],
            workoutHigh = values[7],
            workoutUrgentLow = values[8],
            workoutUrgentHigh = values[9]
        )
    }

    val effectiveThresholds: StateFlow<EffectiveThresholds> = combine(
        state,
        thresholdSnapshot
    ) { mode, snap ->
        val on = mode is WorkoutMode.On
        EffectiveThresholds(
            displayLowMgdl = if (on) snap.workoutLow else snap.bgLow,
            displayHighMgdl = if (on) snap.workoutHigh else snap.bgHigh,
            alertLowMgdl = if (on) snap.workoutLow else snap.alertLow,
            alertHighMgdl = if (on) snap.workoutHigh else snap.alertHigh,
            alertUrgentLowMgdl = if (on) snap.workoutUrgentLow else snap.alertUrgentLow,
            alertUrgentHighMgdl = if (on) snap.workoutUrgentHigh else snap.alertUrgentHigh,
        )
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        EffectiveThresholds(72f, 180f, 72f, 180f, 54f, 234f)
    )

    init {
        // Side-effect: clean up expired DataStore values on each tick.
        // Idempotent — writes null only when value was non-null and expired.
        scope.launch {
            ticker.collect {
                cleanupExpired()
            }
        }
    }

    private suspend fun cleanupExpired() {
        val now = clock.nowMs()
        val manualSince = settings.manualWorkoutSinceMs.first()
        val maxHours = settings.workoutModeMaxHours.first()
        if (manualSince != null && now >= manualSince + maxHours * MS_PER_HOUR) {
            settings.setManualWorkoutSinceMs(null)
        }
        val overrideUntil = settings.manualOffOverrideUntilMs.first()
        if (overrideUntil != null && now >= overrideUntil) {
            settings.setManualOffOverrideUntilMs(null)
        }
    }

    suspend fun setManualOn() {
        settings.setManualWorkoutSinceMs(clock.nowMs())
        settings.setManualOffOverrideUntilMs(null)
    }

    suspend fun setManualOff() {
        val current = state.value
        if (current is WorkoutMode.On && current.source == WorkoutMode.On.Source.CALENDAR) {
            settings.setManualOffOverrideUntilMs(current.expiresAtMs)
        } else {
            settings.setManualWorkoutSinceMs(null)
        }
    }

    suspend fun toggle() {
        if (state.value is WorkoutMode.On) setManualOff() else setManualOn()
    }

    private data class ThresholdSnapshot(
        val bgLow: Float, val bgHigh: Float,
        val alertLow: Float, val alertHigh: Float,
        val alertUrgentLow: Float, val alertUrgentHigh: Float,
        val workoutLow: Float, val workoutHigh: Float,
        val workoutUrgentLow: Float, val workoutUrgentHigh: Float,
    )
}
