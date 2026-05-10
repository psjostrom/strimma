package com.psjostrom.strimma.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationHelperActionTest {

    private lateinit var helper: NotificationHelper

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        helper = NotificationHelper(context)
        helper.createChannel()
    }

    @Test
    fun `NONE produces a notification with no action`() {
        val notification = helper.buildNotification(
            reading = null, recentReadings = emptyList(), bgLow = 70.0, bgHigh = 180.0,
            actionConfig = NotificationActionConfig(
                NotificationActionType.NONE, SnoozeCategory.ALL, SnoozeDuration.H1
            )
        )
        assertEquals(0, notification.actions?.size ?: 0)
    }

    @Test
    fun `WORKOUT_TOGGLE shows Start workout when mode off`() {
        val notification = helper.buildNotification(
            reading = null, recentReadings = emptyList(), bgLow = 70.0, bgHigh = 180.0,
            workoutModeOn = false,
            actionConfig = NotificationActionConfig(
                NotificationActionType.WORKOUT_TOGGLE, SnoozeCategory.ALL, SnoozeDuration.H1
            )
        )
        assertEquals(1, notification.actions.size)
        assertEquals("Start workout", notification.actions[0].title.toString())
    }

    @Test
    fun `WORKOUT_TOGGLE shows End workout when mode on`() {
        val notification = helper.buildNotification(
            reading = null, recentReadings = emptyList(), bgLow = 70.0, bgHigh = 180.0,
            workoutModeOn = true,
            actionConfig = NotificationActionConfig(
                NotificationActionType.WORKOUT_TOGGLE, SnoozeCategory.ALL, SnoozeDuration.H1
            )
        )
        assertEquals(1, notification.actions.size)
        assertEquals("End workout", notification.actions[0].title.toString())
    }

    @Test
    fun `SNOOZE All 1h shows Snooze all 1h label`() {
        val notification = helper.buildNotification(
            reading = null, recentReadings = emptyList(), bgLow = 70.0, bgHigh = 180.0,
            actionConfig = NotificationActionConfig(
                NotificationActionType.SNOOZE, SnoozeCategory.ALL, SnoozeDuration.H1
            )
        )
        assertEquals("Snooze all 1h", notification.actions[0].title.toString())
    }

    @Test
    fun `SNOOZE High 30m shows Snooze high 30m label`() {
        val notification = helper.buildNotification(
            reading = null, recentReadings = emptyList(), bgLow = 70.0, bgHigh = 180.0,
            actionConfig = NotificationActionConfig(
                NotificationActionType.SNOOZE, SnoozeCategory.HIGH, SnoozeDuration.M30
            )
        )
        assertEquals("Snooze high 30m", notification.actions[0].title.toString())
    }

    @Test
    fun `SNOOZE Low 3h shows Snooze low 3h label`() {
        val notification = helper.buildNotification(
            reading = null, recentReadings = emptyList(), bgLow = 70.0, bgHigh = 180.0,
            actionConfig = NotificationActionConfig(
                NotificationActionType.SNOOZE, SnoozeCategory.LOW, SnoozeDuration.H3
            )
        )
        assertEquals("Snooze low 3h", notification.actions[0].title.toString())
    }

    @Test
    fun `every snooze category and duration combination produces an action`() {
        for (cat in SnoozeCategory.entries) {
            for (dur in SnoozeDuration.entries) {
                val notification = helper.buildNotification(
                    reading = null, recentReadings = emptyList(), bgLow = 70.0, bgHigh = 180.0,
                    actionConfig = NotificationActionConfig(
                        NotificationActionType.SNOOZE, cat, dur
                    )
                )
                assertEquals("snooze action missing for $cat/$dur", 1, notification.actions.size)
                assertTrue(
                    "label empty for $cat/$dur",
                    notification.actions[0].title.toString().isNotEmpty()
                )
            }
        }
    }
}
