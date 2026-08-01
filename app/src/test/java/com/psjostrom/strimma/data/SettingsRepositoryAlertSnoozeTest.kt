package com.psjostrom.strimma.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.notification.SnoozeDuration
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryAlertSnoozeTest {

    private fun kotlinx.coroutines.test.TestScope.repo(): SettingsRepository {
        val context: Context = ApplicationProvider.getApplicationContext()
        return SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore(this))
    }

    @Test
    fun `alertSnoozeDuration defaults to M30`() = runTest {
        assertEquals(SnoozeDuration.M30, repo().alertSnoozeDuration.first())
    }

    @Test
    fun `alertSnoozeDuration round-trips each enum value`() = runTest {
        val r = repo()
        for (dur in SnoozeDuration.entries) {
            r.setAlertSnoozeDuration(dur)
            assertEquals(dur, r.alertSnoozeDuration.first())
        }
    }

    @Test
    fun `alert snooze is independent of notif snooze duration`() = runTest {
        val r = repo()
        r.setAlertSnoozeDuration(SnoozeDuration.H1)
        r.setNotifSnoozeDuration(SnoozeDuration.M15)
        assertEquals(SnoozeDuration.H1, r.alertSnoozeDuration.first())
        assertEquals(SnoozeDuration.M15, r.notifSnoozeDuration.first())
        assertNotEquals(r.alertSnoozeDuration.first(), r.notifSnoozeDuration.first())
    }

    @Test
    fun `export and import round-trip alert_snooze_duration`() = runTest {
        val source = repo()
        source.setAlertSnoozeDuration(SnoozeDuration.H2)
        val json = source.exportToJson()
        assertEquals("H2", JSONObject(json).getJSONObject("settings").getString("alert_snooze_duration"))

        val target = repo()
        target.importFromJson(json)
        assertEquals(SnoozeDuration.H2, target.alertSnoozeDuration.first())
        assertEquals(SnoozeDuration.H1, target.notifSnoozeDuration.first())
    }

    @Test
    fun `import normalizes invalid alert_snooze_duration to M30`() = runTest {
        val r = repo()
        val json = JSONObject()
            .put("version", 2)
            .put("settings", JSONObject().put("alert_snooze_duration", "NOT_A_DURATION"))
            .toString()
        r.importFromJson(json)
        assertEquals(SnoozeDuration.M30, r.alertSnoozeDuration.first())
        assertEquals(
            "M30",
            JSONObject(r.exportToJson()).getJSONObject("settings").getString("alert_snooze_duration")
        )
    }
}
