package com.psjostrom.strimma.data.workout

import com.psjostrom.strimma.data.calendar.WorkoutEvent
import kotlinx.coroutines.flow.StateFlow

/**
 * Seam over CalendarPoller for testability. Production binds to CalendarPoller;
 * tests provide a hand-written fake.
 */
interface CalendarPollerSource {
    val nextEvent: StateFlow<WorkoutEvent?>
}
