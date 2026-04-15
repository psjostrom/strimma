package com.psjostrom.strimma.data.story

import com.psjostrom.strimma.data.GlucoseStats
import org.junit.Assert.*
import org.junit.Test

class StoryNarrativeTest {

    private fun stats(tir: Double = 78.0, below: Double = 2.0, above: Double = 20.0) =
        GlucoseStats(
            count = 40000, averageMgdl = 140.0, stdDevMgdl = 40.0,
            cv = 28.6, gmi = 6.5, tirPercent = tir,
            belowPercent = below, abovePercent = above, periodLabel = "month"
        )

    private fun stability(flatlineMinutes: Int? = 167, streakMinutes: Int? = 1102) =
        StabilityData(
            longestFlatline = flatlineMinutes?.let {
                FlatlineStretch(0L, it * 60_000L, it, 1.0)
            },
            flatlineCount = 23,
            longestInRangeStreak = streakMinutes?.let {
                InRangeStreak(0L, it * 60_000L, it)
            },
            steadiestDay = null
        )

    private fun events(low: Int = 7, high: Int = 31, prevLow: Int? = 12, prevHigh: Int? = 38) =
        EventData(low, high, prevLow, prevHigh, 1.8, 20.2, 23, 48)

    private fun timeOfDay() = TimeOfDayData(
        listOf(
            TimeBlockStats("Night", 0, 6, 92.0, 10000),
            TimeBlockStats("Morning", 6, 12, 64.0, 10000),
            TimeBlockStats("Afternoon", 12, 18, 81.0, 10000),
            TimeBlockStats("Evening", 18, 24, 68.0, 10000)
        )
    )

    @Test
    fun `generate includes TIR improvement when previous month exists and TIR increased`() {
        val text = StoryNarrative.generate(
            stats = stats(tir = 78.0),
            previousStats = stats(tir = 74.0),
            stability = stability(),
            events = events(),
            timeOfDay = timeOfDay(),
            monthLabel = "March"
        )
        assertTrue("Should mention TIR improvement", text.contains("78"))
        assertTrue(
            "Should mention increase",
            text.contains("74") || text.lowercase().contains("up") || text.lowercase().contains("climbed")
        )
    }

    @Test
    fun `generate uses This month when no previous stats`() {
        val text = StoryNarrative.generate(
            stats = stats(),
            previousStats = null,
            stability = stability(),
            events = events(prevLow = null, prevHigh = null),
            timeOfDay = timeOfDay(),
            monthLabel = "March"
        )
        assertFalse("Should not compare to previous", text.lowercase().contains("from"))
    }

    @Test
    fun `generate mentions flatline when 2h or more`() {
        val text = StoryNarrative.generate(
            stats = stats(),
            previousStats = null,
            stability = stability(flatlineMinutes = 167),
            events = events(prevLow = null, prevHigh = null),
            timeOfDay = timeOfDay(),
            monthLabel = "March"
        )
        assertTrue("Should mention flatline", text.lowercase().contains("flatline"))
    }

    @Test
    fun `generate mentions best time block when 85 percent or higher`() {
        val text = StoryNarrative.generate(
            stats = stats(),
            previousStats = null,
            stability = stability(),
            events = events(prevLow = null, prevHigh = null),
            timeOfDay = timeOfDay(),
            monthLabel = "March"
        )
        assertTrue("Should mention Night as sweet spot", text.contains("Night"))
    }

    @Test
    fun `generate mentions worst time block when under 60 percent`() {
        val todWithBadBlock = TimeOfDayData(
            listOf(
                TimeBlockStats("Night", 0, 6, 92.0, 10000),
                TimeBlockStats("Morning", 6, 12, 55.0, 10000),
                TimeBlockStats("Afternoon", 12, 18, 81.0, 10000),
                TimeBlockStats("Evening", 18, 24, 68.0, 10000)
            )
        )
        val text = StoryNarrative.generate(
            stats = stats(),
            previousStats = null,
            stability = stability(),
            events = events(prevLow = null, prevHigh = null),
            timeOfDay = todWithBadBlock,
            monthLabel = "March"
        )
        assertTrue("Should mention Morning", text.contains("Morning"))
    }

    @Test
    fun `generate produces 3 to 5 sentences`() {
        val text = StoryNarrative.generate(
            stats = stats(),
            previousStats = stats(tir = 74.0),
            stability = stability(),
            events = events(),
            timeOfDay = timeOfDay(),
            monthLabel = "March"
        )
        val sentenceCount = text.count { it == '.' }
        assertTrue("Should have 3-5 sentences, got $sentenceCount: $text", sentenceCount in 3..5)
    }
}
