package com.psjostrom.strimma.data.story

import com.psjostrom.strimma.data.GlucoseStats

object StoryNarrative {

    private const val SWEET_SPOT_TIR_THRESHOLD = 85.0
    private const val WORST_BLOCK_TIR_THRESHOLD = 60.0
    private const val MIN_FLATLINE_MINUTES = 120
    private const val MINUTES_PER_HOUR = 60
    private const val MAX_FRAGMENTS = 5
    private const val TIR_DELTA_THRESHOLD = 2.0

    fun generate(
        stats: GlucoseStats,
        previousStats: GlucoseStats?,
        stability: StabilityData,
        events: EventData,
        timeOfDay: TimeOfDayData,
        monthLabel: String
    ): String {
        val fragments = mutableListOf<String>()

        fragments.add(tirFragment(stats, previousStats, monthLabel))

        lowsFragment(events)?.let { fragments.add(it) }

        val best = timeOfDay.blocks.filter { it.readingCount > 0 }.maxByOrNull { it.tirPercent }
        if (best != null && best.tirPercent >= SWEET_SPOT_TIR_THRESHOLD) {
            fragments.add("${best.name} is your sweet spot at ${best.tirPercent.toInt()}% TIR.")
        }

        val worst = timeOfDay.blocks.filter { it.readingCount > 0 }.minByOrNull { it.tirPercent }
        if (worst != null && worst.tirPercent < WORST_BLOCK_TIR_THRESHOLD) {
            fragments.add("${worst.name} is where the next win is \u2014 ${worst.tirPercent.toInt()}% TIR.")
        }

        val flatline = stability.longestFlatline
        if (flatline != null && flatline.durationMinutes >= MIN_FLATLINE_MINUTES) {
            val hours = flatline.durationMinutes / MINUTES_PER_HOUR
            val minutes = flatline.durationMinutes % MINUTES_PER_HOUR
            fragments.add("You held a ${hours}h ${minutes}m flatline \u2014 rock steady.")
        }

        return fragments.take(MAX_FRAGMENTS).joinToString(" ")
    }

    private fun tirFragment(stats: GlucoseStats, prev: GlucoseStats?, monthLabel: String): String {
        if (prev == null) {
            return "This $monthLabel, time in range was ${formatTir(stats.tirPercent)}%."
        }
        val delta = stats.tirPercent - prev.tirPercent
        return when {
            delta >= TIR_DELTA_THRESHOLD ->
                "Time in range climbed to ${formatTir(stats.tirPercent)}%, " +
                    "up from ${formatTir(prev.tirPercent)}%."
            delta <= -TIR_DELTA_THRESHOLD ->
                "Time in range dipped to ${formatTir(stats.tirPercent)}%, " +
                    "down from ${formatTir(prev.tirPercent)}%."
            else -> "Time in range held steady at ${formatTir(stats.tirPercent)}%."
        }
    }

    private fun lowsFragment(events: EventData): String? {
        val prev = events.previousLowEvents ?: return null
        return when {
            events.lowEvents < prev -> "Lows dropped from $prev to ${events.lowEvents} events."
            events.lowEvents > prev -> "Lows crept up to ${events.lowEvents} events, from $prev last month."
            else -> null
        }
    }

    private fun formatTir(tir: Double): String {
        return if (tir == tir.toLong().toDouble()) tir.toLong().toString()
        else "%.1f".format(tir)
    }
}
