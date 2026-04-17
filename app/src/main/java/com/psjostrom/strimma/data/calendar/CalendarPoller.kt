package com.psjostrom.strimma.data.calendar

import com.psjostrom.strimma.data.MS_PER_HOUR
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.receiver.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarPoller @Inject constructor(
    private val calendarReader: CalendarReader,
    private val settings: SettingsRepository
) {
    private val _nextEvent = MutableStateFlow<WorkoutEvent?>(null)
    val nextEvent: StateFlow<WorkoutEvent?> = _nextEvent

    fun start(scope: CoroutineScope): Job {
        return scope.launch {
            // Reactive: re-poll when calendarId or lookahead changes
            launch {
                combine(
                    settings.workoutCalendarId,
                    settings.workoutLookaheadHours
                ) { calId, hours -> calId to hours }
                    .collect { (calId, hours) ->
                        _nextEvent.value = poll(calId, hours)
                    }
            }
            // Periodic: poll every minute for external calendar changes
            while (currentCoroutineContext().isActive) {
                delay(MS_PER_MINUTE)
                _nextEvent.value = poll(
                    settings.workoutCalendarId.first(),
                    settings.workoutLookaheadHours.first()
                )
            }
        }
    }

    private suspend fun poll(calendarId: Long, lookaheadHours: Int): WorkoutEvent? {
        if (calendarId < 0) return null
        return try {
            calendarReader.getNextWorkout(calendarId, lookaheadHours.toLong() * MS_PER_HOUR)
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            DebugLog.log("Calendar poll failed: ${e.message}")
            null
        }
    }
}
