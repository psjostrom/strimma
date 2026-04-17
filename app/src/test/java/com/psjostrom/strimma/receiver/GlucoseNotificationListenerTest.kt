package com.psjostrom.strimma.receiver

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.psjostrom.strimma.data.GlucoseSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

private const val MGDL_FACTOR = 18.0182

@RunWith(RobolectricTestRunner::class)
class GlucoseNotificationListenerTest {

    private lateinit var listener: GlucoseNotificationListener

    @Before
    fun setup() {
        listener = Robolectric.setupService(GlucoseNotificationListener::class.java)
        setSource(GlucoseSource.COMPANION)
    }

    private fun setSource(source: GlucoseSource) {
        listener.getSharedPreferences("strimma_sync", Context.MODE_PRIVATE)
            .edit().putString("glucose_source", source.name).apply()
    }

    private fun buildSbn(
        packageName: String,
        ongoing: Boolean,
        notification: Notification,
    ): StatusBarNotification {
        if (ongoing) {
            notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT
        }
        @Suppress("DEPRECATION")
        return StatusBarNotification(
            packageName, packageName, 1, null, 0, 0, 0,
            notification, android.os.Process.myUserHandle(),
            System.currentTimeMillis(),
        )
    }

    private fun notificationWithTitle(title: String): Notification =
        Notification().apply {
            extras = Bundle().apply {
                putString(Notification.EXTRA_TITLE, title)
            }
        }

    private fun nextStartedService(): Intent? =
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedService

    @Test
    fun `non-ongoing notification from bypass package is processed`() {
        val sbn = buildSbn(
            "com.senseonics.eversense365.us",
            ongoing = false,
            notificationWithTitle("120"),
        )
        listener.onNotificationPosted(sbn)

        val intent = nextStartedService()
        assertNotNull("Bypass package should be processed even when not ongoing", intent)
        assertEquals(GlucoseNotificationListener.ACTION_GLUCOSE_RECEIVED, intent!!.action)
        assertEquals(120.0, intent.getDoubleExtra(GlucoseNotificationListener.EXTRA_MGDL, 0.0), 0.1)
    }

    @Test
    fun `non-ongoing notification from non-bypass CGM package is rejected`() {
        val sbn = buildSbn(
            "com.camdiab.fx_alert.mmoll",
            ongoing = false,
            notificationWithTitle("5,7"),
        )
        listener.onNotificationPosted(sbn)

        assertNull("Non-bypass package should be rejected when not ongoing", nextStartedService())
    }

    @Test
    fun `ongoing notification from CGM package is processed`() {
        val sbn = buildSbn(
            "com.camdiab.fx_alert.mmoll",
            ongoing = true,
            notificationWithTitle("5,7"),
        )
        listener.onNotificationPosted(sbn)

        val intent = nextStartedService()
        assertNotNull("Ongoing CGM notification should always be processed", intent)
        assertEquals(GlucoseNotificationListener.ACTION_GLUCOSE_RECEIVED, intent!!.action)
    }

    @Test
    fun `tickerText fallback extracts glucose when extras are empty`() {
        val notification = Notification().apply {
            tickerText = "5.7"
        }
        val sbn = buildSbn("com.camdiab.fx_alert.mmoll", ongoing = true, notification)
        listener.onNotificationPosted(sbn)

        val intent = nextStartedService()
        assertNotNull("Should extract glucose from tickerText as fallback", intent)
        assertEquals(
            5.7 * MGDL_FACTOR,
            intent!!.getDoubleExtra(GlucoseNotificationListener.EXTRA_MGDL, 0.0),
            0.5,
        )
    }
}
