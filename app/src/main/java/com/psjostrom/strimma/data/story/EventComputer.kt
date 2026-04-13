package com.psjostrom.strimma.data.story

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.MS_PER_MINUTE

object EventComputer {
    private const val LOW_THRESHOLD_MGDL = 70.0
    private const val HIGH_THRESHOLD_MGDL = 180.0
    private const val GAP_MINUTES = 15

    data class EventStats(
        val lowEvents: Int,
        val highEvents: Int,
        val belowPercent: Double,
        val abovePercent: Double,
        val avgLowDurationMinutes: Int?,
        val avgHighDurationMinutes: Int?
    )

    fun compute(readings: List<GlucoseReading>): EventStats {
        if (readings.isEmpty()) return EventStats(0, 0, 0.0, 0.0, null, null)
        val sorted = readings.sortedBy { it.ts }
        val belowCount = sorted.count { it.sgv < LOW_THRESHOLD_MGDL }
        val aboveCount = sorted.count { it.sgv > HIGH_THRESHOLD_MGDL }
        val belowPct = belowCount.toDouble() / sorted.size * 100
        val abovePct = aboveCount.toDouble() / sorted.size * 100
        val lowEvents = findEvents(sorted) { it.sgv < LOW_THRESHOLD_MGDL }
        val highEvents = findEvents(sorted) { it.sgv > HIGH_THRESHOLD_MGDL }
        val avgLow = if (lowEvents.isNotEmpty()) {
            lowEvents.map { it.durationMinutes }.average().toInt()
        } else null
        val avgHigh = if (highEvents.isNotEmpty()) {
            highEvents.map { it.durationMinutes }.average().toInt()
        } else null
        return EventStats(lowEvents.size, highEvents.size, belowPct, abovePct, avgLow, avgHigh)
    }

    private data class Event(val startTs: Long, val endTs: Long, val durationMinutes: Int)

    private fun findEvents(
        sorted: List<GlucoseReading>,
        isOutOfRange: (GlucoseReading) -> Boolean
    ): List<Event> {
        val events = mutableListOf<Event>()
        var eventStart: Long? = null
        var lastOutTs: Long? = null
        for (r in sorted) {
            if (isOutOfRange(r)) {
                if (eventStart == null) eventStart = r.ts
                lastOutTs = r.ts
            } else if (eventStart != null) {
                val gapMinutes = (r.ts - lastOutTs!!) / MS_PER_MINUTE
                if (gapMinutes >= GAP_MINUTES) {
                    val duration = ((lastOutTs - eventStart) / MS_PER_MINUTE).toInt()
                    events.add(Event(eventStart, lastOutTs, duration))
                    eventStart = null
                    lastOutTs = null
                }
            }
        }
        if (eventStart != null && lastOutTs != null) {
            val duration = ((lastOutTs - eventStart) / MS_PER_MINUTE).toInt()
            events.add(Event(eventStart, lastOutTs, duration))
        }
        return events
    }
}
