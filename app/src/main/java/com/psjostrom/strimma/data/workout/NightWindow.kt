package com.psjostrom.strimma.data.workout

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * A daily local-time window, in minutes from local midnight (0..1439).
 * The window may wrap midnight (start later than end). End is exclusive.
 * Membership uses the wall-clock time, so a DST switch night does not shift the window.
 */
data class NightWindow(val startMinute: Int, val endMinute: Int) {

    fun contains(time: LocalTime): Boolean {
        val t = time.hour * MINUTES_PER_HOUR + time.minute
        return if (startMinute < endMinute) t in startMinute until endMinute else t >= startMinute || t < endMinute
    }

    fun contains(nowMs: Long, zone: ZoneId): Boolean =
        contains(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime())

    companion object {
        private const val MINUTES_PER_HOUR = 60
        val DEFAULT = NightWindow(startMinute = 23 * MINUTES_PER_HOUR, endMinute = 7 * MINUTES_PER_HOUR)
    }
}
