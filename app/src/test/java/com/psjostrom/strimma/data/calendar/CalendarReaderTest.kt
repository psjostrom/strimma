package com.psjostrom.strimma.data.calendar

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class CalendarReaderTest {

    @Test
    fun `hasPermission returns false when not granted`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        val reader = CalendarReader(app)
        assertFalse(reader.hasPermission())
    }

    @Test
    fun `hasPermission returns true when granted`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        val reader = CalendarReader(app)
        assertTrue(reader.hasPermission())
    }

    @Test
    fun `hasPermission reflects runtime changes`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadow = Shadows.shadowOf(app)
        val reader = CalendarReader(app)

        shadow.denyPermissions(Manifest.permission.READ_CALENDAR)
        assertFalse(reader.hasPermission())

        shadow.grantPermissions(Manifest.permission.READ_CALENDAR)
        assertTrue(reader.hasPermission())
    }
}
