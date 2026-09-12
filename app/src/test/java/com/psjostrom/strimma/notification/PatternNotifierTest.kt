package com.psjostrom.strimma.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.pattern.GlucosePattern
import com.psjostrom.strimma.data.pattern.PatternResult
import com.psjostrom.strimma.data.pattern.PatternType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PatternNotifierTest {

    private data class Fixture(
        val notifier: PatternNotifier,
        val notificationManager: NotificationManager
    )

    private fun createFixture(): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
        val notifier = PatternNotifier(context)
        return Fixture(notifier, notificationManager)
    }

    @Test
    fun `notifyPatterns with single LOW pattern formats mmol-L and posts to pattern channel`() {
        val fix = createFixture()
        val pattern = GlucosePattern(
            startHour = 6,
            endHour = 7,
            type = PatternType.LOW,
            daysDetected = 4,
            daysEvaluated = 6,
            avgBgMgdl = 65.0
        )

        fix.notifier.notifyPatterns(PatternResult(listOf(pattern)), GlucoseUnit.MMOL)

        val active = fix.notificationManager.activeNotifications
            .firstOrNull { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN }
        assertNotNull(active)
        assertEquals(AlertManager.CHANNEL_PATTERN, active!!.notification.channelId)

        val title = active.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = active.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        assertNotNull(title)
        assertNotNull(text)
        assertTrue(title!!.contains("06:00–07:00"))
        assertTrue(text!!.contains("3.6 mmol/L"))
        assertTrue(text.contains("4"))
        assertTrue(text.contains("6"))
    }

    @Test
    fun `notifyPatterns with single HIGH pattern formats mg-dL`() {
        val fix = createFixture()
        val pattern = GlucosePattern(
            startHour = 14,
            endHour = 16,
            type = PatternType.HIGH,
            daysDetected = 5,
            daysEvaluated = 7,
            avgBgMgdl = 210.0
        )

        fix.notifier.notifyPatterns(PatternResult(listOf(pattern)), GlucoseUnit.MGDL)

        val active = fix.notificationManager.activeNotifications
            .firstOrNull { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN }
        assertNotNull(active)

        val title = active!!.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = active.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        assertNotNull(title)
        assertNotNull(text)
        assertTrue(title!!.contains("14:00–16:00"))
        assertTrue(text!!.contains("210 mg/dL"))
    }

    @Test
    fun `notifyPatterns with multiple patterns builds InboxStyle with lines per pattern`() {
        val fix = createFixture()
        val patterns = listOf(
            GlucosePattern(
                startHour = 7,
                endHour = 8,
                type = PatternType.LOW,
                daysDetected = 4,
                daysEvaluated = 6,
                avgBgMgdl = 60.0
            ),
            GlucosePattern(
                startHour = 15,
                endHour = 17,
                type = PatternType.HIGH,
                daysDetected = 5,
                daysEvaluated = 7,
                avgBgMgdl = 220.0
            )
        )

        fix.notifier.notifyPatterns(PatternResult(patterns), GlucoseUnit.MMOL)

        val active = fix.notificationManager.activeNotifications
            .firstOrNull { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN }
        assertNotNull(active)

        val title = active!!.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        assertTrue(title!!.contains("2"))

        val lines = active.notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        assertNotNull(lines)
        assertEquals(2, lines!!.size)
        assertTrue(lines[0].toString().contains("07:00–08:00"))
        assertTrue(lines[1].toString().contains("15:00–17:00"))
    }

    @Test
    fun `notifyPatterns with empty patterns cancels existing notification`() {
        val fix = createFixture()
        val pattern = GlucosePattern(
            startHour = 6,
            endHour = 7,
            type = PatternType.LOW,
            daysDetected = 4,
            daysEvaluated = 6,
            avgBgMgdl = 65.0
        )
        fix.notifier.notifyPatterns(PatternResult(listOf(pattern)), GlucoseUnit.MMOL)
        assertTrue(fix.notificationManager.activeNotifications.any { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN })

        fix.notifier.notifyPatterns(PatternResult(emptyList()), GlucoseUnit.MMOL)
        assertFalse(fix.notificationManager.activeNotifications.any { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN })
    }

    @Test
    fun `cancel removes active notification`() {
        val fix = createFixture()
        val pattern = GlucosePattern(
            startHour = 6,
            endHour = 7,
            type = PatternType.LOW,
            daysDetected = 4,
            daysEvaluated = 6,
            avgBgMgdl = 65.0
        )
        fix.notifier.notifyPatterns(PatternResult(listOf(pattern)), GlucoseUnit.MMOL)
        assertTrue(fix.notificationManager.activeNotifications.any { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN })

        fix.notifier.cancel()
        assertFalse(fix.notificationManager.activeNotifications.any { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN })
    }
}
