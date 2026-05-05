package com.psjostrom.strimma.data.workout

/**
 * Runtime state of workout mode. Timestamps are epoch milliseconds (Long),
 * matching Strimma's existing convention.
 */
sealed class WorkoutMode {
    data object Off : WorkoutMode()

    data class On(
        val source: Source,
        val sinceMs: Long,
        /** For MANUAL: sinceMs + maxHours*MS_PER_HOUR. For CALENDAR: event.endTime. */
        val expiresAtMs: Long
    ) : WorkoutMode() {
        enum class Source { MANUAL, CALENDAR }
    }
}
