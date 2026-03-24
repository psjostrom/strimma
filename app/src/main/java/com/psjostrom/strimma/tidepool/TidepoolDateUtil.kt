package com.psjostrom.strimma.tidepool

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TidepoolDateUtil {

    private const val UTC_ISO8601_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'0000Z'"
    private const val LOCAL_NO_ZONE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss"
    private const val MS_PER_MINUTE = 60_000

    /**
     * Formats timestamp as UTC ISO8601 with Tidepool's required format:
     * yyyy-MM-dd'T'HH:mm:ss.SSS0000Z
     */
    fun toUtcIso8601(timestamp: Long): String {
        val formatter = SimpleDateFormat(UTC_ISO8601_FORMAT, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return formatter.format(Date(timestamp))
    }

    /**
     * Formats timestamp as local time without timezone suffix:
     * yyyy-MM-dd'T'HH:mm:ss
     */
    fun toLocalNoZone(timestamp: Long): String {
        val formatter = SimpleDateFormat(LOCAL_NO_ZONE_FORMAT, Locale.US)
        return formatter.format(Date(timestamp))
    }

    /**
     * Returns timezone offset in minutes for the given timestamp.
     * Positive offset = ahead of UTC, negative = behind UTC.
     */
    fun getTimezoneOffsetMinutes(timestamp: Long): Int {
        return TimeZone.getDefault().getOffset(timestamp) / MS_PER_MINUTE
    }
}
