package com.psjostrom.strimma.data.story

import com.psjostrom.strimma.data.GlucoseStats

object StoryNarrative {

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
        if (best != null && best.tirPercent >= 85.0) {
            fragments.add("${best.name} is your sweet spot at ${best.tirPercent.toInt()}% TIR.")
        }

        val worst = timeOfDay.blocks.filter { it.readingCount > 0 }.minByOrNull { it.tirPercent }
        if (worst != null && worst.tirPercent < 60.0) {
            fragments.add("${worst.name} is where the next win is \u2014 ${worst.tirPercent.toInt()}% TIR.")
        }

        val flatline = stability.longestFlatline
        if (flatline != null && flatline.durationMinutes >= 120) {
            val hours = flatline.durationMinutes / 60
            val minutes = flatline.durationMinutes % 60
            fragments.add("You held a ${hours}h ${minutes}m flatline \u2014 rock steady.")
        }

        return fragments.take(5).joinToString(" ")
    }

    private fun tirFragment(stats: GlucoseStats, prev: GlucoseStats?, monthLabel: String): String {
        if (prev == null) {
            return "This $monthLabel, time in range was ${formatTir(stats.tirPercent)}%."
        }
        val delta = stats.tirPercent - prev.tirPercent
        return when {
            delta >= 2.0 -> "Time in range climbed to ${formatTir(stats.tirPercent)}%, up from ${formatTir(prev.tirPercent)}%."
            delta <= -2.0 -> "Time in range dipped to ${formatTir(stats.tirPercent)}%, down from ${formatTir(prev.tirPercent)}%."
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
