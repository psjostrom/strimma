package com.psjostrom.strimma.data.workout

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class NightWindowTest {
    private val window = NightWindow.DEFAULT

    @Test fun `start minute is inside`() = assertTrue(window.contains(LocalTime.of(23, 0)))
    @Test fun `end minute is outside`() = assertFalse(window.contains(LocalTime.of(7, 0)))
    @Test fun `minute before end is inside`() = assertTrue(window.contains(LocalTime.of(6, 59)))
    @Test fun `midday is outside`() = assertFalse(window.contains(LocalTime.of(14, 0)))

    @Test fun `window inside one day`() {
        val w = NightWindow(startMinute = 60, endMinute = 300)
        assertTrue(w.contains(LocalTime.of(1, 0)))
        assertFalse(w.contains(LocalTime.of(5, 0)))
        assertFalse(w.contains(LocalTime.of(0, 59)))
    }

    @Test fun `DST switch night uses wall clock`() {
        val zone = ZoneId.of("Europe/Stockholm")
        val at0330 = ZonedDateTime.of(2026, 3, 29, 3, 30, 0, 0, zone).toInstant().toEpochMilli()
        val at0730 = ZonedDateTime.of(2026, 3, 29, 7, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertTrue(window.contains(at0330, zone))
        assertFalse(window.contains(at0730, zone))
    }
}
