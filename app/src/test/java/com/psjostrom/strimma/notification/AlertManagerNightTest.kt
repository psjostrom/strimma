package com.psjostrom.strimma.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.workout.AlertSet
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import com.psjostrom.strimma.testutil.workout.FakeCalendarPoller
import com.psjostrom.strimma.testutil.workout.MutableClock
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

private const val TIMEOUT_MS = 5_000L
private const val HIGH_READING_MGDL = 200
private const val LOW_READING_MGDL = 60

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AlertManagerNightTest {

    private class Rig(
        val alertManager: AlertManager,
        val notificationManager: NotificationManager,
        val clock: MutableClock,
    ) {
        fun notification(id: Int): Notification? = Shadows.shadowOf(notificationManager).getNotification(id)
    }

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 6, 10, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    // Settings and clock are applied before the manager exists, so its first real emission is already final.
    private suspend fun TestScope.rig(
        hour: Int,
        minute: Int = 0,
        nightHighEnabled: Boolean = true,
        expected: AlertSet,
    ): Rig {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore(this))
        settings.setNightAlertsEnabled(true)
        settings.setNightAlertHighEnabled(nightHighEnabled)
        val clock = MutableClock(at(hour, minute)).apply { zone = ZoneId.of("UTC") }
        val manager = WorkoutModeManager(settings, FakeCalendarPoller(), clock, backgroundScope)
        withTimeout(TIMEOUT_MS) {
            manager.effectiveThresholds.first {
                it !== WorkoutModeManager.PLACEHOLDER_THRESHOLDS && it.activeSet == expected
            }
        }
        val alertManager = AlertManager(context, settings, manager, clock)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        alertManager.createChannels()
        notificationManager.cancelAll()
        return Rig(alertManager, notificationManager, clock)
    }

    private fun reading(mgdl: Int): GlucoseReading =
        GlucoseReading(ts = at(0), sgv = mgdl, direction = "Flat", delta = 0.0, pushed = 0)

    @Test
    fun `night set with High off raises no High alert at 02 00`() = runTest {
        val rig = rig(hour = 2, nightHighEnabled = false, expected = AlertSet.NIGHT)
        rig.alertManager.checkReading(reading(HIGH_READING_MGDL), emptyList(), predictionMinutes = 0)
        assertNull(rig.notification(AlertManager.ALERT_HIGH_ID))
    }

    @Test
    fun `same reading at 14 00 raises the Day High alert`() = runTest {
        val rig = rig(hour = 14, nightHighEnabled = false, expected = AlertSet.DAY)
        rig.alertManager.checkReading(reading(HIGH_READING_MGDL), emptyList(), predictionMinutes = 0)
        val notification = rig.notification(AlertManager.ALERT_HIGH_ID)
        assertNotNull(notification)
        assertEquals("High Glucose", notification!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    // The clock moves without advancing virtual time, so the 30 s ticker never re-evaluates the set.
    @Test
    fun `reading at 07 00 uses the day set before the ticker runs`() = runTest {
        val rig = rig(hour = 6, minute = 59, nightHighEnabled = false, expected = AlertSet.NIGHT)
        rig.clock.nowMs = at(7)
        rig.alertManager.checkReading(reading(HIGH_READING_MGDL), emptyList(), predictionMinutes = 0)
        val notification = rig.notification(AlertManager.ALERT_HIGH_ID)
        assertNotNull(notification)
        assertEquals("High Glucose", notification!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `reading at 23 00 uses the night set before the ticker runs`() = runTest {
        val rig = rig(hour = 22, minute = 59, nightHighEnabled = false, expected = AlertSet.DAY)
        rig.clock.nowMs = at(23)
        rig.alertManager.checkReading(reading(HIGH_READING_MGDL), emptyList(), predictionMinutes = 0)
        assertNull(rig.notification(AlertManager.ALERT_HIGH_ID))
    }

    @Test
    fun `night Low alert title has the Night prefix`() = runTest {
        val rig = rig(hour = 2, expected = AlertSet.NIGHT)
        rig.alertManager.checkReading(reading(LOW_READING_MGDL), emptyList(), predictionMinutes = 0)
        val notification = rig.notification(AlertManager.ALERT_LOW_ID)
        assertNotNull(notification)
        assertEquals("Night · Low Glucose", notification!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }
}
