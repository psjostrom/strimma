package com.psjostrom.strimma.data.story

import com.psjostrom.strimma.data.GlucoseReading
import java.time.Instant
import java.time.ZoneId

object TimeOfDayComputer {

    private const val PERCENT = 100

    private val BLOCK_DEFS = listOf(
        Triple("Night", 0, 6),
        Triple("Morning", 6, 12),
        Triple("Afternoon", 12, 18),
        Triple("Evening", 18, 24)
    )

    fun compute(
        readings: List<GlucoseReading>,
        bgLow: Double,
        bgHigh: Double,
        zone: ZoneId
    ): TimeOfDayData {
        val grouped = readings.groupBy { r ->
            val hour = Instant.ofEpochMilli(r.ts).atZone(zone).hour
            BLOCK_DEFS.indexOfFirst { hour >= it.second && hour < it.third }
        }
        val blocks = BLOCK_DEFS.mapIndexed { index, (name, start, end) ->
            val blockReadings = grouped[index] ?: emptyList()
            if (blockReadings.isEmpty()) {
                TimeBlockStats(name, start, end, 0.0, 0)
            } else {
                val inRange = blockReadings.count { it.sgv >= bgLow && it.sgv <= bgHigh }
                val tir = inRange.toDouble() / blockReadings.size * PERCENT
                TimeBlockStats(name, start, end, tir, blockReadings.size)
            }
        }
        return TimeOfDayData(blocks)
    }
}
