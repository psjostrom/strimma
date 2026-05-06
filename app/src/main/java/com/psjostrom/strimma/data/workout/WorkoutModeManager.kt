package com.psjostrom.strimma.data.workout

import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import com.psjostrom.strimma.receiver.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
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
 *   R1. manualSince != null && now < manualExpiresAt           → On(MANUAL)
 *   R3. overrideUntil != null && now < overrideUntil           → Off
 *   R5. nextEvent != null && start <= now <= end               → On(CALENDAR)
 *   else                                                        → Off
 *
 * Persistence: manualSinceMs and manualExpiresMs are written together at toggle
 * time so a clock jump or a mid-session change to maxHours can never extend or
 * invalidate an active session.
 *
 * Cleanup of expired flags is folded into the same 30-s ticker that drives state
 * recomputation — they share a single SharedFlow so we don't burn two timers.
 */
@Singleton
class WorkoutModeManager @Inject constructor(
    private val settings: SettingsRepository,
    private val calendarSource: CalendarPollerSource,
    private val clock: Clock,
    private val scope: CoroutineScope
) {
    /**
     * Single ticker shared by both the state-derivation combine and the cleanup
     * collector. shareIn with replay=1 lets late subscribers get the latest tick
     * without missing the first one.
     */
    private val ticker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_INTERVAL_MS)
        }
    }.shareIn(scope, SharingStarted.Eagerly, replay = 1)

    private val stateInputs: Flow<StateInputs> = combine(
        settings.manualWorkoutSinceMs,
        settings.manualWorkoutExpiresMs,
        settings.manualOffOverrideUntilMs,
        settings.workoutModeMaxHours,
        calendarSource.nextEvent
    ) { manualSince, manualExpires, overrideUntil, maxHours, nextEvent ->
        StateInputs(manualSince, manualExpires, overrideUntil, maxHours, nextEvent)
    }

    val state: StateFlow<WorkoutMode> = stateInputs.combine(ticker) { inputs, _ ->
        computeState(
            manualSince = inputs.manualSince,
            manualExpires = inputs.manualExpires,
            overrideUntil = inputs.overrideUntil,
            maxHours = inputs.maxHours,
            nextEvent = inputs.nextEvent,
            now = clock.nowMs()
        )
    }.stateIn(scope, SharingStarted.Eagerly, WorkoutMode.Off)

    /**
     * Pure state derivation — no side effects, fully testable in isolation.
     *
     * `manualExpires` is the canonical expiry (snapshotted at toggle). For
     * sessions persisted by older app versions without an expiresMs, the caller
     * (or this function as a fallback) computes it from sinceMs + maxHours. When
     * maxHours is changed mid-session, an existing manualExpires is preserved.
     */
    internal fun computeState(
        manualSince: Long?,
        manualExpires: Long?,
        overrideUntil: Long?,
        maxHours: Int,
        nextEvent: WorkoutEvent?,
        now: Long
    ): WorkoutMode {
        // R1: manual ON, not yet expired. effectiveExpires is non-null whenever
        // manualSince is non-null (either persisted explicitly or computed from
        // the per-session maxHours fallback), so smart-cast carries through.
        if (manualSince != null) {
            val effectiveExpires = manualExpires ?: (manualSince + maxHours * MS_PER_HOUR)
            if (now < effectiveExpires) {
                return WorkoutMode.On(
                    source = WorkoutMode.On.Source.MANUAL,
                    sinceMs = manualSince,
                    expiresAtMs = effectiveExpires
                )
            }
        }
        // R3: manual OFF override active
        if (overrideUntil != null && now < overrideUntil) {
            return WorkoutMode.Off
        }
        // R5: calendar event currently active
        if (nextEvent != null && now in nextEvent.startTime..nextEvent.endTime) {
            return WorkoutMode.On(
                source = WorkoutMode.On.Source.CALENDAR,
                sinceMs = nextEvent.startTime,
                expiresAtMs = nextEvent.endTime
            )
        }
        return WorkoutMode.Off
    }

    /** Combined snapshot of all threshold settings. */
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

    /**
     * Sentinel-seeded so consumers can distinguish "manager hasn't loaded yet"
     * (PLACEHOLDER_THRESHOLDS) from a real emission. Use [currentEffectiveThresholds]
     * for one-shot reads — it suspends until real data arrives instead of returning
     * the sentinel.
     */
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
    }.stateIn(scope, SharingStarted.Eagerly, PLACEHOLDER_THRESHOLDS)

    /**
     * Suspending one-shot read of the current threshold set. Waits until the
     * upstream combine has produced a real value — protects every consumer that
     * needs synchronous-feeling access (AlertManager, LocalWebServer, widget,
     * service) from cold-start race against the placeholder seed.
     */
    suspend fun currentEffectiveThresholds(): EffectiveThresholds =
        effectiveThresholds.first { it !== PLACEHOLDER_THRESHOLDS }

    /**
     * Read current state directly from persistence — does NOT trust state.value,
     * which can lag the truth across the eager seed and across cold start before
     * the upstream combine produces its first real emission. Use this from
     * code paths that branch on the state correctness (toggle, setManualOff,
     * stale-alert suppression decision); fine for the StateFlow to lag in
     * eventually-consistent UI consumers.
     */
    suspend fun currentState(): WorkoutMode {
        val manualSince = settings.manualWorkoutSinceMs.first()
        val manualExpires = settings.manualWorkoutExpiresMs.first()
        val overrideUntil = settings.manualOffOverrideUntilMs.first()
        val maxHours = settings.workoutModeMaxHours.first()
        val nextEvent = calendarSource.nextEvent.value
        return computeState(manualSince, manualExpires, overrideUntil, maxHours, nextEvent, clock.nowMs())
    }

    /**
     * Elapsed time in the current workout session, or `null` if not currently in
     * workout mode. Computed against the manager's injected [Clock] so tests with
     * a [Clock] override see a consistent timeline (rather than a mix of test
     * time for `state.sinceMs` and real wall-clock time for the elapsed calc).
     */
    suspend fun currentSessionElapsedMs(): Long? {
        val state = currentState()
        return (state as? WorkoutMode.On)?.let { (clock.nowMs() - it.sinceMs).coerceAtLeast(0L) }
    }

    init {
        // Side-effect 1: clean expired DataStore values on every tick. Atomic
        // edit prevents a read-decide-write race against a concurrent
        // setManualWorkoutSession from clobbering a freshly-toggled session.
        // try/catch keeps the cleanup loop alive across transient DataStore
        // exceptions — without it, a single IOException would leave the auto-off
        // safety timeout permanently broken.
        scope.launch {
            ticker.collect {
                try {
                    settings.cleanupExpiredWorkoutState(clock.nowMs(), MS_PER_HOUR)
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    // DataStore can throw IOException, SecurityException, or KeyStore-related
                    // exceptions; we swallow + log to keep the cleanup loop alive.
                    @Suppress("TooGenericExceptionCaught") e: Exception
                ) {
                    DebugLog.log("workout cleanupExpired failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        // Side-effect 2: clear an outstanding override when the calendar event it
        // was bound to disappears. Without this, deleting the active event leaves
        // a phantom override in DataStore that suppresses any new event scheduled
        // for the same window.
        scope.launch {
            calendarSource.nextEvent.collect { event ->
                if (event == null && settings.manualOffOverrideUntilMs.first() != null) {
                    settings.setManualOffOverrideUntilMs(null)
                }
            }
        }
    }

    /**
     * Snapshot expiresAt = now + maxHours at toggle time. A subsequent change to
     * maxHours does not retroactively shorten this session; a clock jump does
     * not extend or kill it. Auto-off acts on the snapshotted absolute deadline.
     */
    suspend fun setManualOn() {
        val nowMs = clock.nowMs()
        val maxHours = settings.workoutModeMaxHours.first()
        val expiresMs = nowMs + maxHours * MS_PER_HOUR
        settings.setManualWorkoutSession(sinceMs = nowMs, expiresMs = expiresMs)
        settings.setManualOffOverrideUntilMs(null)
    }

    /**
     * Honor the user's "off" intent regardless of which source currently presents.
     * If a calendar event is also active right now, we set the override so the
     * state machine routes to Off until that event ends — independently of whether
     * the current source happens to be MANUAL (because the user toggled on during
     * the event) or CALENDAR.
     */
    suspend fun setManualOff() {
        val nowMs = clock.nowMs()
        val event = calendarSource.nextEvent.value
        if (event != null && nowMs in event.startTime..event.endTime) {
            settings.setManualOffOverrideUntilMs(event.endTime)
        }
        settings.setManualWorkoutSession(sinceMs = null, expiresMs = null)
    }

    /**
     * Read state directly from persistence rather than trusting state.value —
     * which can be the eager-seeded WorkoutMode.Off until the upstream combine
     * has emitted. This is exactly the surface the foreground-notification
     * action receiver hits on a freshly-respawned process.
     */
    suspend fun toggle() {
        val current = currentState()
        if (current is WorkoutMode.On) setManualOff() else setManualOn()
    }

    private data class StateInputs(
        val manualSince: Long?,
        val manualExpires: Long?,
        val overrideUntil: Long?,
        val maxHours: Int,
        val nextEvent: WorkoutEvent?,
    )

    private data class ThresholdSnapshot(
        val bgLow: Float, val bgHigh: Float,
        val alertLow: Float, val alertHigh: Float,
        val alertUrgentLow: Float, val alertUrgentHigh: Float,
        val workoutLow: Float, val workoutHigh: Float,
        val workoutUrgentLow: Float, val workoutUrgentHigh: Float,
    )

    companion object {
        /**
         * Sentinel returned by [effectiveThresholds] until the upstream combine has
         * produced a real value. Identity-compared against by [currentEffectiveThresholds]
         * to filter it out, so distinguishing the placeholder from real defaults is
         * by reference rather than by value (a user could legitimately have these
         * exact thresholds — won't be confused).
         */
        val PLACEHOLDER_THRESHOLDS = EffectiveThresholds(
            displayLowMgdl = Float.NaN,
            displayHighMgdl = Float.NaN,
            alertLowMgdl = Float.NaN,
            alertHighMgdl = Float.NaN,
            alertUrgentLowMgdl = Float.NaN,
            alertUrgentHighMgdl = Float.NaN,
        )
    }
}
