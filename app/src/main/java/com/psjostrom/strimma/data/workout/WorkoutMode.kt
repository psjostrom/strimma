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
        /**
         * Absolute deadline at which this session ends. Snapshotted at toggle
         * time so a later change to `workoutModeMaxHours` cannot retroactively
         * shorten this session, and a clock jump cannot extend or invalidate it.
         *
         * For MANUAL: now + maxHours*MS_PER_HOUR (captured at setManualOn).
         * For CALENDAR: the active event's endTime.
         */
        val expiresAtMs: Long
    ) : WorkoutMode() {
        enum class Source { MANUAL, CALENDAR }
    }
}
