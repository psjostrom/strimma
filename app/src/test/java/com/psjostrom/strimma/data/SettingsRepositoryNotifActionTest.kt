package com.psjostrom.strimma.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.notification.NotificationActionType
import com.psjostrom.strimma.data.notification.SnoozeCategory
import com.psjostrom.strimma.data.notification.SnoozeDuration
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryNotifActionTest {

    private fun kotlinx.coroutines.test.TestScope.repo(): SettingsRepository {
        val context: Context = ApplicationProvider.getApplicationContext()
        return SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore(this))
    }

    @Test
    fun `notifActionType defaults to WORKOUT_TOGGLE`() = runTest {
        assertEquals(NotificationActionType.WORKOUT_TOGGLE, repo().notifActionType.first())
    }

    @Test
    fun `notifActionType round-trips each enum value`() = runTest {
        val r = repo()
        for (type in NotificationActionType.entries) {
            r.setNotifActionType(type)
            assertEquals(type, r.notifActionType.first())
        }
    }

    @Test
    fun `notifSnoozeCategory defaults to ALL`() = runTest {
        assertEquals(SnoozeCategory.ALL, repo().notifSnoozeCategory.first())
    }

    @Test
    fun `notifSnoozeCategory round-trips each enum value`() = runTest {
        val r = repo()
        for (cat in SnoozeCategory.entries) {
            r.setNotifSnoozeCategory(cat)
            assertEquals(cat, r.notifSnoozeCategory.first())
        }
    }

    @Test
    fun `notifSnoozeDuration defaults to H1`() = runTest {
        assertEquals(SnoozeDuration.H1, repo().notifSnoozeDuration.first())
    }

    @Test
    fun `notifSnoozeDuration round-trips each enum value`() = runTest {
        val r = repo()
        for (dur in SnoozeDuration.entries) {
            r.setNotifSnoozeDuration(dur)
            assertEquals(dur, r.notifSnoozeDuration.first())
        }
    }

    @Test
    fun `the three settings are independent`() = runTest {
        val r = repo()
        r.setNotifActionType(NotificationActionType.SNOOZE)
        r.setNotifSnoozeCategory(SnoozeCategory.HIGH)
        r.setNotifSnoozeDuration(SnoozeDuration.M30)

        assertEquals(NotificationActionType.SNOOZE, r.notifActionType.first())
        assertEquals(SnoozeCategory.HIGH, r.notifSnoozeCategory.first())
        assertEquals(SnoozeDuration.M30, r.notifSnoozeDuration.first())
    }

    @Test
    fun `export then import round-trips notif action settings`() = runTest {
        val source = repo()
        source.setNotifActionType(NotificationActionType.SNOOZE)
        source.setNotifSnoozeCategory(SnoozeCategory.HIGH)
        source.setNotifSnoozeDuration(SnoozeDuration.M30)
        val json = source.exportToJson()

        // Fresh repo (separate DataStore) restores from the export
        val target = repo()
        target.importFromJson(json)

        assertEquals(NotificationActionType.SNOOZE, target.notifActionType.first())
        assertEquals(SnoozeCategory.HIGH, target.notifSnoozeCategory.first())
        assertEquals(SnoozeDuration.M30, target.notifSnoozeDuration.first())
    }
}
