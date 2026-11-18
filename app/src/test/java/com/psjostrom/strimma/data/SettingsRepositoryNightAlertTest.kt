package com.psjostrom.strimma.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.workout.NightWindow
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryNightAlertTest {

    private fun kotlinx.coroutines.test.TestScope.makeFixture(): SettingsRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore(this))
    }

    @Test
    fun `night alerts default off with 23 to 07 window`() = runTest {
        val s = makeFixture()
        assertFalse(s.nightAlertsEnabled.first())
        assertEquals(NightWindow(1380, 420), s.nightWindow.first())
    }

    @Test
    fun `first enable copies the day set`() = runTest {
        val s = makeFixture()
        s.setAlertHighEnabled(false)
        s.setAlertLow(80f)
        s.setNightAlertsEnabled(true)
        assertEquals(s.regularAlertProtocol.first(), s.nightAlertProtocol.first())
    }

    @Test
    fun `day edit after enable does not change night set`() = runTest {
        val s = makeFixture()
        s.setNightAlertsEnabled(true)
        s.setAlertLow(90f)
        assertEquals(72f, s.nightAlertProtocol.first().lowMgdl)
    }

    @Test
    fun `second enable keeps the edited night set`() = runTest {
        val s = makeFixture()
        s.setNightAlertsEnabled(true)
        s.setNightAlertHighEnabled(false)
        s.setNightAlertsEnabled(false)
        s.setAlertHighEnabled(true)
        s.setNightAlertsEnabled(true)
        assertFalse(s.nightAlertProtocol.first().highEnabled)
    }

    @Test
    fun `export and import round trip keeps night settings`() = runTest {
        val a = makeFixture()
        a.setNightAlertsEnabled(true)
        a.setNightAlertHighSoonEnabled(false)
        a.setNightWindow(NightWindow(22 * 60, 6 * 60))
        val b = makeFixture()
        b.importFromJson(a.exportToJson())
        assertTrue(b.nightAlertsEnabled.first())
        assertEquals(NightWindow(1320, 360), b.nightWindow.first())
        assertEquals(a.nightAlertProtocol.first(), b.nightAlertProtocol.first())
    }

    @Test
    fun `imported night set survives a later first enable`() = runTest {
        val a = makeFixture()
        a.setNightAlertsEnabled(true)
        a.setNightAlertLow(90f)
        a.setNightAlertsEnabled(false)
        val b = makeFixture()
        b.importFromJson(a.exportToJson())
        b.setNightAlertsEnabled(true)
        assertEquals(90f, b.nightAlertProtocol.first().lowMgdl)
    }

    @Test
    fun `import without night keys leaves night settings unchanged`() = runTest {
        val s = makeFixture()
        s.setNightAlertsEnabled(true)
        s.setNightWindow(NightWindow(22 * 60, 6 * 60))
        s.setNightAlertLow(90f)
        s.setNightAlertHighEnabled(false)
        val nightBefore = s.nightAlertProtocol.first()
        val oldFile = JSONObject().put("alert_low", 80.0).put("alert_high_enabled", true)
        s.importFromJson(JSONObject().put("version", 3).put("settings", oldFile).toString())
        assertTrue(s.nightAlertsEnabled.first())
        assertEquals(NightWindow(1320, 360), s.nightWindow.first())
        assertEquals(nightBefore, s.nightAlertProtocol.first())
        assertEquals(90f, s.nightAlertProtocol.first().lowMgdl)
        assertFalse(s.nightAlertProtocol.first().highEnabled)
    }

    @Test
    fun `import of a window with equal start and end keeps the stored window`() = runTest {
        val s = makeFixture()
        s.setNightWindow(NightWindow(22 * 60, 6 * 60))
        val settings = JSONObject().put("night_alerts_start_minute", 480).put("night_alerts_end_minute", 480)
        s.importFromJson(JSONObject().put("version", 3).put("settings", settings).toString())
        assertEquals(NightWindow(1320, 360), s.nightWindow.first())
    }

    @Test
    fun `import enabling night without night fields copies the imported day set`() = runTest {
        val s = makeFixture()
        val settings = JSONObject().put("night_alerts_enabled", true).put("alert_high_enabled", false)
        s.importFromJson(JSONObject().put("version", 3).put("settings", settings).toString())
        assertEquals(s.regularAlertProtocol.first(), s.nightAlertProtocol.first())
        assertFalse(s.nightAlertProtocol.first().highEnabled)
    }

    @Test
    fun `export from a device that never enabled night leaves the target copying its day set`() = runTest {
        val a = makeFixture()
        a.setAlertLow(80f)
        a.setAlertHighEnabled(false)
        val b = makeFixture()
        b.importFromJson(a.exportToJson())
        b.setNightAlertsEnabled(true)
        assertEquals(b.regularAlertProtocol.first(), b.nightAlertProtocol.first())
        assertEquals(80f, b.nightAlertProtocol.first().lowMgdl)
        assertFalse(b.nightAlertProtocol.first().highEnabled)
    }

    @Test
    fun `partial night import fills the other fields from the day set`() = runTest {
        val s = makeFixture()
        val settings = JSONObject().put("night_alert_low", 90.0).put("alert_high_enabled", false)
        s.importFromJson(JSONObject().put("version", 3).put("settings", settings).toString())
        assertEquals(90f, s.nightAlertProtocol.first().lowMgdl)
        assertFalse(s.nightAlertProtocol.first().highEnabled)
    }
}
