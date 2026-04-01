package com.psjostrom.strimma.data.health

import com.psjostrom.strimma.data.GlucoseReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ExerciseBGAnalyzerTest {

    private val analyzer = ExerciseBGAnalyzer()

    // Helpers

    private val baseTime = Instant.parse("2026-03-24T08:00:00Z").toEpochMilli()

    private fun minutes(n: Long) = Duration.ofMinutes(n).toMillis()

    private fun reading(minutesFromBase: Long, sgv: Int) = GlucoseReading(
        ts = baseTime + minutes(minutesFromBase),
        sgv = sgv,
        direction = "Flat",
        delta = null,
        pushed = 1
    )

    private fun hrSample(sessionId: String, minutesFromBase: Long, bpm: Int) = HeartRateSample(
        sessionId = sessionId,
        time = baseTime + minutes(minutesFromBase),
        bpm = bpm
    )

    private fun session(
        startMinutes: Long = 0,
        endMinutes: Long = 60,
        id: String = "test-session",
        totalSteps: Int? = 5000,
        activeCalories: Double? = 350.0
    ) = StoredExerciseSession(
        id = id,
        type = 8, // RUNNING
        startTime = baseTime + minutes(startMinutes),
        endTime = baseTime + minutes(endMinutes),
        title = "Test Run",
        totalSteps = totalSteps,
        activeCalories = activeCalories
    )

    /** Generate 1-minute-interval readings across a time range with a linear BG slope. */
    private fun linearReadings(
        startMinute: Long,
        endMinute: Long,
        startSgv: Int,
        endSgv: Int
    ): List<GlucoseReading> {
        val count = (endMinute - startMinute).toInt() + 1
        return (0 until count).map { i ->
            val t = startMinute + i
            val sgv = startSgv + ((endSgv - startSgv) * i.toDouble() / (count - 1)).toInt()
            reading(t, sgv)
        }
    }

    /** Generate flat readings at a fixed SGV. */
    private fun flatReadings(startMinute: Long, endMinute: Long, sgv: Int): List<GlucoseReading> =
        linearReadings(startMinute, endMinute, sgv, sgv)

    // --- Tests ---

    @Test
    fun `normal session with BG drop during exercise`() {
        // Pre: 30 min of stable BG at 140
        // During (0-60): drops from 140 to 90
        // Post (60-300): stays flat at 90
        val pre = flatReadings(-30, -1, 140)
        val during = linearReadings(0, 60, 140, 90)
        val post = flatReadings(61, 300, 90)
        val allReadings = pre + during + post

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 60),
            readings = allReadings,
            heartRateSamples = emptyList(),
            bgLowMgdl = 70.0
        )

        assertNotNull(result)
        result!!

        assertEquals(140, result.entryBG)
        assertEquals(Trend.STABLE, result.entryTrend)
        assertEquals(90, result.minBG)
        assertTrue(result.dropPer10Min.isNotEmpty())

        assertFalse(result.postExerciseHypo)
        assertEquals(90, result.lowestBG)
        assertEquals(90, result.highestBG) // flat post = same as lowest
        assertEquals(5000, result.totalSteps)
        assertEquals(350.0, result.activeCalories!!, 0.01)
    }

    @Test
    fun `session with post-exercise hypo`() {
        val pre = flatReadings(-30, -1, 130)
        val during = linearReadings(0, 60, 130, 100)
        // Post: drops further to 60 (below 70 threshold), then recovers
        val postDrop = linearReadings(61, 120, 100, 60)
        val postRecover = linearReadings(121, 300, 60, 110)
        val allReadings = pre + during + postDrop + postRecover

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 60),
            readings = allReadings,
            heartRateSamples = emptyList(),
            bgLowMgdl = 70.0
        )

        assertNotNull(result)
        result!!

        assertTrue(result.postExerciseHypo)
        assertEquals(60, result.lowestBG)
        assertNotNull(result.lowestBGTime)
    }

    @Test
    fun `returns null when fewer than 2 readings during exercise`() {
        val single = listOf(reading(0, 140))

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 60),
            readings = single,
            heartRateSamples = emptyList(),
            bgLowMgdl = 70.0
        )

        assertNull(result)
    }

    @Test
    fun `pre-exercise rising trend detected`() {
        // Pre: 30 min of rising BG (100 -> 160 = 2 mg/dL per min)
        val pre = linearReadings(-30, -1, 100, 160)
        val during = flatReadings(0, 60, 160)
        val allReadings = pre + during

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 60),
            readings = allReadings,
            heartRateSamples = emptyList(),
            bgLowMgdl = 70.0
        )

        assertNotNull(result)
        assertEquals(Trend.RISING, result!!.entryTrend)
        assertEquals(160, result.entryBG)
        assertNotNull(result.entryStability)
    }

    @Test
    fun `pre-exercise falling trend detected`() {
        // Pre: 30 min of falling BG (160 -> 100 = -2 mg/dL per min)
        val pre = linearReadings(-30, -1, 160, 100)
        val during = flatReadings(0, 60, 100)
        val allReadings = pre + during

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 60),
            readings = allReadings,
            heartRateSamples = emptyList(),
            bgLowMgdl = 70.0
        )

        assertNotNull(result)
        assertEquals(Trend.FALLING, result!!.entryTrend)
        assertEquals(100, result.entryBG)
    }

    @Test
    fun `very short session under 10 minutes`() {
        // 8-minute session with 1-min readings
        val pre = flatReadings(-30, -1, 130)
        val during = linearReadings(0, 8, 130, 120)
        val post = flatReadings(9, 248, 120)
        val allReadings = pre + during + post

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 8),
            readings = allReadings,
            heartRateSamples = emptyList(),
            bgLowMgdl = 70.0
        )

        assertNotNull(result)
        result!!

        assertEquals(120, result.minBG)
        // Partial bucket still computed (readings exist in the 0-10 min window)
        assertEquals(1, result.dropPer10Min.size)

    }

    @Test
    fun `HR data aggregated correctly`() {
        val during = flatReadings(0, 60, 140)
        val pre = flatReadings(-30, -1, 140)
        val hrSamples = listOf(
            hrSample("test-session", 5, 120),
            hrSample("test-session", 15, 145),
            hrSample("test-session", 30, 160),
            hrSample("test-session", 45, 155),
            hrSample("test-session", 55, 140)
        )

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 60),
            readings = pre + during,
            heartRateSamples = hrSamples,
            bgLowMgdl = 70.0
        )

        assertNotNull(result)
        result!!

        // avg of 120, 145, 160, 155, 140 = 144
        assertEquals(144, result.avgHR)
        assertEquals(160, result.maxHR)
    }

    @Test
    fun `no HR data results in null avgHR and maxHR`() {
        val pre = flatReadings(-30, -1, 140)
        val during = flatReadings(0, 60, 140)

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 60),
            readings = pre + during,
            heartRateSamples = emptyList(),
            bgLowMgdl = 70.0
        )

        assertNotNull(result)
        assertNull(result!!.avgHR)
        assertNull(result.maxHR)
    }

    @Test
    fun `no pre-window readings gives null entryBG and entryTrend`() {
        // No readings before start, only during
        val during = flatReadings(0, 60, 140)

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 60),
            readings = during,
            heartRateSamples = emptyList(),
            bgLowMgdl = 70.0
        )

        assertNotNull(result)
        result!!

        assertNull(result.entryBG)
        assertNull(result.entryTrend)
        assertNull(result.entryStability)
    }

    @Test
    fun `exactly 2 readings during exercise should not return null`() {
        val during = listOf(reading(0, 140), reading(60, 100))

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 60),
            readings = during,
            heartRateSamples = emptyList(),
            bgLowMgdl = 70.0
        )

        assertNotNull(result)
    }

    @Test
    fun `drop per 10 min buckets computed correctly`() {
        // 30-minute session. BG drops 10 mg/dL every 10 minutes.
        // 150 -> 140 (first bucket), 140 -> 130 (second), 130 -> 120 (third)
        val pre = flatReadings(-30, -1, 150)
        val during = linearReadings(0, 30, 150, 120)

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 30),
            readings = pre + during,
            heartRateSamples = emptyList(),
            bgLowMgdl = 70.0
        )

        assertNotNull(result)
        result!!

        // Each bucket should show ~10 mg/dL drop
        assertTrue(result.dropPer10Min.isNotEmpty())
        for (drop in result.dropPer10Min) {
            assertTrue("Drop should be approximately 10, was $drop", drop in 8.0..12.0)
        }
        assertEquals(result.maxDropRate!!, result.dropPer10Min.max(), 0.01)
    }

    @Test
    fun `post-exercise spike tracked as highestBG`() {
        val pre = flatReadings(-30, -1, 140)
        val during = linearReadings(0, 60, 140, 100)
        // Post: drops to 80, then spikes to 200, then settles at 150
        val postDrop = linearReadings(61, 90, 98, 80)
        val postSpike = linearReadings(91, 120, 82, 200)
        val postSettle = linearReadings(121, 300, 198, 150)
        val allReadings = pre + during + postDrop + postSpike + postSettle

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 60),
            readings = allReadings,
            heartRateSamples = emptyList(),
            bgLowMgdl = 70.0
        )

        assertNotNull(result)
        result!!

        assertEquals(80, result.lowestBG)
        assertEquals(200, result.highestBG)
        assertNotNull(result.highestBGTime)
        // Highest should be at ~120 min mark
        val minutesAfterEnd = (result.highestBGTime!!.toEpochMilli() - (baseTime + minutes(60))) / 60_000
        assertTrue("Highest should be around 60 min after end", minutesAfterEnd in 55..65)
    }

    @Test
    fun `lowestBG is from post window only, not during exercise`() {
        val pre = flatReadings(-30, -1, 140)
        // During: BG drops to 70
        val during = linearReadings(0, 60, 140, 70)
        // Post: BG is 80 (higher than during-exercise min of 70)
        val post = flatReadings(61, 300, 80)
        val allReadings = pre + during + post

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 60),
            readings = allReadings,
            heartRateSamples = emptyList(),
            bgLowMgdl = 70.0
        )

        assertNotNull(result)
        result!!

        // minBG (during) = 70, lowestBG (post) = 80
        assertEquals(70, result.minBG)
        assertEquals(80, result.lowestBG)
    }

    @Test
    fun `no post readings gives null post fields and no hypo`() {
        val pre = flatReadings(-30, -1, 140)
        val during = flatReadings(0, 60, 140)

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 60),
            readings = pre + during,
            heartRateSamples = emptyList(),
            bgLowMgdl = 70.0
        )

        assertNotNull(result)
        result!!

        assertNull(result.lowestBG)
        assertNull(result.lowestBGTime)
        assertNull(result.highestBG)
        assertNull(result.highestBGTime)
        assertFalse(result.postExerciseHypo)
    }

    @Test
    fun `minBG reflects during-exercise low even when post-window dips lower`() {
        // Real-world scenario: BG is stable during run (stays ~150-130),
        // but crashes to 70 in the 4h post-exercise window (delayed hypo).
        // The card "Lowest" should show 130 (during), not 70 (post).
        val pre = flatReadings(-30, -1, 150)
        val during = linearReadings(0, 60, 150, 130)
        val postDrop = linearReadings(61, 120, 128, 70)
        val postRecover = linearReadings(121, 300, 72, 110)
        val allReadings = pre + during + postDrop + postRecover

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 60),
            readings = allReadings,
            heartRateSamples = emptyList(),
            bgLowMgdl = 72.0
        )

        assertNotNull(result)
        result!!

        assertEquals(130, result.minBG)    // during-exercise low
        assertEquals(70, result.lowestBG)  // post-exercise low (much lower)
        assertTrue(result.postExerciseHypo)
    }

    @Test
    fun `minBG when BG rises during exercise`() {
        // BG rises during exercise (e.g. adrenaline spike). minBG should be the start value.
        val pre = flatReadings(-30, -1, 100)
        val during = linearReadings(0, 60, 100, 180)
        val post = flatReadings(61, 300, 170)
        val allReadings = pre + during + post

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 60),
            readings = allReadings,
            heartRateSamples = emptyList(),
            bgLowMgdl = 72.0
        )

        assertNotNull(result)
        result!!

        assertEquals(100, result.minBG)   // lowest point is the start
        assertEquals(170, result.lowestBG) // post-window is higher
        assertFalse(result.postExerciseHypo)
    }

    @Test
    fun `sparse during exercise with dense post window still shows analysis`() {
        // Dexcom (5-min) during run, Libre 3 (1-min) in post-window.
        val pre = (0..5).map { reading(-30L + it * 5, 140) }
        val during = (0..8).map { reading(it * 5L, 140 - it * 5) }
        val post = flatReadings(41, 280, 100)

        val result = analyzer.analyze(
            session = session(startMinutes = 0, endMinutes = 40),
            readings = pre + during + post,
            heartRateSamples = emptyList(),
            bgLowMgdl = 70.0
        )

        assertNotNull(result)
        assertEquals(100, result!!.minBG)
    }
}
