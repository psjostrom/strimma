package com.psjostrom.strimma.testutil.workout

import com.psjostrom.strimma.data.calendar.WorkoutEvent
import com.psjostrom.strimma.data.workout.CalendarPollerSource
import com.psjostrom.strimma.data.workout.Clock
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Hand-written Clock test double — simply returns whatever timestamp the test
 * sets on it. Lets tests advance "wall-clock" time independently of virtual
 * dispatcher time (which is needed because some assertions are about
 * elapsed-since-toggle, not about coroutine scheduling).
 */
class MutableClock(var nowMs: Long) : Clock {
    override fun nowMs(): Long = nowMs
}

/**
 * Hand-written test double for [CalendarPollerSource]. Mirrors the production
 * shape — a `nextEvent` StateFlow — without dragging Calendar Provider into
 * the test environment.
 */
class FakeCalendarPoller(
    val nextEventFlow: MutableStateFlow<WorkoutEvent?> = MutableStateFlow(null)
) : CalendarPollerSource {
    override val nextEvent get() = nextEventFlow
}
