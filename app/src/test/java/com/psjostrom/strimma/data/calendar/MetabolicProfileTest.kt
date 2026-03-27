package com.psjostrom.strimma.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class MetabolicProfileTest {

    @Test
    fun `AEROBIC has correct default targets`() {
        assertEquals(126f, MetabolicProfile.AEROBIC.defaultTargetLowMgdl)
        assertEquals(180f, MetabolicProfile.AEROBIC.defaultTargetHighMgdl)
    }

    @Test
    fun `HIGH_INTENSITY has higher default targets`() {
        assertEquals(144f, MetabolicProfile.HIGH_INTENSITY.defaultTargetLowMgdl)
        assertEquals(216f, MetabolicProfile.HIGH_INTENSITY.defaultTargetHighMgdl)
    }

    @Test
    fun `RESISTANCE has same targets as AEROBIC`() {
        assertEquals(126f, MetabolicProfile.RESISTANCE.defaultTargetLowMgdl)
        assertEquals(180f, MetabolicProfile.RESISTANCE.defaultTargetHighMgdl)
    }

    @Test
    fun `fromKeywords detects high intensity`() {
        assertEquals(MetabolicProfile.HIGH_INTENSITY, MetabolicProfile.fromKeywords("Interval Run"))
        assertEquals(MetabolicProfile.HIGH_INTENSITY, MetabolicProfile.fromKeywords("Tempo session"))
        assertEquals(MetabolicProfile.HIGH_INTENSITY, MetabolicProfile.fromKeywords("HIIT workout"))
        assertEquals(MetabolicProfile.HIGH_INTENSITY, MetabolicProfile.fromKeywords("Sprint training"))
        assertEquals(MetabolicProfile.HIGH_INTENSITY, MetabolicProfile.fromKeywords("Fartlek"))
        assertEquals(MetabolicProfile.HIGH_INTENSITY, MetabolicProfile.fromKeywords("Intervall"))
    }

    @Test
    fun `fromKeywords returns null for non-intensity words`() {
        assertEquals(null, MetabolicProfile.fromKeywords("Easy Run"))
        assertEquals(null, MetabolicProfile.fromKeywords("Morning Walk"))
        assertEquals(null, MetabolicProfile.fromKeywords("Gym"))
        assertEquals(null, MetabolicProfile.fromKeywords(""))
    }

    @Test
    fun `fromKeywords is case insensitive`() {
        assertEquals(MetabolicProfile.HIGH_INTENSITY, MetabolicProfile.fromKeywords("INTERVAL"))
        assertEquals(MetabolicProfile.HIGH_INTENSITY, MetabolicProfile.fromKeywords("hiit"))
    }
}
