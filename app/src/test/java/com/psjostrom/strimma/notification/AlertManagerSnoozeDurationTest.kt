package com.psjostrom.strimma.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.R
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.notification.SnoozeDuration
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import com.psjostrom.strimma.testutil.workout.FakeCalendarPoller
import com.psjostrom.strimma.testutil.workout.MutableClock
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Snooze-duration tests — no shared @Before. Each test arranges its own
 * SettingsRepository, SharedPreferences, notification manager, AlertManager, and scope.
 */
@RunWith(RobolectricTestRunner::class)
class AlertManagerSnoozeDurationTest {

    private fun reading(sgv: Int, ts: Long = System.currentTimeMillis()): GlucoseReading =
        GlucoseReading(ts = ts, sgv = sgv, direction = "Flat", delta = 0.0)

    private suspend fun withFixture(
        alertSnooze: SnoozeDuration = SnoozeDuration.M30,
        block: suspend (Context, AlertManager, NotificationManager) -> Unit,
    ) {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val localSettings = SettingsRepository(ctx, WidgetSettingsRepository(ctx), createTestDataStore())
        localSettings.setAlertSnoozeDuration(alertSnooze)
        localSettings.setAlertLowEnabled(true)
        localSettings.setAlertStaleEnabled(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            ctx.getSharedPreferences("strimma_snooze", Context.MODE_PRIVATE).edit().clear().apply()
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.cancelAll()
            val am = AlertManager(
                ctx,
                localSettings,
                WorkoutModeManager(
                    localSettings,
                    FakeCalendarPoller(),
                    MutableClock(System.currentTimeMillis()),
                    scope,
                ),
                scope,
            ).also { it.createChannels() }
            block(ctx, am, nm)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `snooze with H1 duration sets category pause ~1 hour ahead`() = runTest {
        withFixture { _, am, _ ->
            am.checkReading(reading(60), emptyList(), 0)
            val before = System.currentTimeMillis()
            am.snooze(AlertManager.ALERT_LOW_ID, SnoozeDuration.H1.durationMs)
            val expiry = am.alertPauseExpiryMs(AlertCategory.LOW)
            assertNotNull(expiry)
            val expectedMin = before + SnoozeDuration.H1.durationMs - 5_000
            val expectedMax = before + SnoozeDuration.H1.durationMs + 5_000
            assertTrue("expiry=$expiry", expiry!! in expectedMin..expectedMax)
        }
    }

    @Test
    fun `snooze default duration remains ~30 minutes`() = runTest {
        withFixture { _, am, _ ->
            am.checkReading(reading(60), emptyList(), 0)
            val before = System.currentTimeMillis()
            am.snooze(AlertManager.ALERT_LOW_ID)
            val expiry = am.alertPauseExpiryMs(AlertCategory.LOW)
            assertNotNull(expiry)
            val expectedMin = before + SnoozeDuration.M30.durationMs - 5_000
            val expectedMax = before + SnoozeDuration.M30.durationMs + 5_000
            assertTrue("expiry=$expiry", expiry!! in expectedMin..expectedMax)
        }
    }

    @Test
    fun `stale snooze with custom duration stores per-alert until`() = runTest {
        withFixture { ctx, am, _ ->
            val before = System.currentTimeMillis()
            am.snooze(AlertManager.ALERT_STALE_ID, SnoozeDuration.H2.durationMs)
            val until = ctx.getSharedPreferences("strimma_snooze", Context.MODE_PRIVATE)
                .getLong(AlertManager.ALERT_STALE_ID.toString(), 0L)
            assertTrue(until >= before + SnoozeDuration.H2.durationMs - 5_000)
            assertTrue(until <= before + SnoozeDuration.H2.durationMs + 5_000)
        }
    }

    @Test
    fun `persisted H1 is baked into snooze action on immediately fired alert`() = runTest {
        withFixture(SnoozeDuration.H1) { ctx, am, nm ->
            am.checkReading(reading(60), emptyList(), 0)

            val notif = nm.activeNotifications.first { it.id == AlertManager.ALERT_LOW_ID }
            val actions = notif.notification.actions
            assertNotNull(actions)
            assertTrue(actions!!.isNotEmpty())
            assertEquals(
                ctx.getString(R.string.alert_snooze, ctx.getString(R.string.snooze_duration_1h)),
                actions[0].title.toString()
            )
            val snoozeIntent = Shadows.shadowOf(actions[0].actionIntent).savedIntent
            assertEquals(
                SnoozeDuration.H1.name,
                snoozeIntent.getStringExtra(AlertSnoozeReceiver.EXTRA_DURATION)
            )
        }
    }
}
