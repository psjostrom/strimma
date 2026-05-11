package com.psjostrom.strimma.notification

import android.content.Context
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseReading
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the rendering contract that `StrimmaService.startStaleCheckLoop` depends on:
 * each call to `updateNotification` must produce a since-text that reflects the current
 * wall-clock elapsed time from the reading. If a future refactor re-introduces a gate
 * around the per-tick `updateNotification()` call (see PR #223), the user-visible "Xm"
 * counter would freeze between readings — these tests guard against that by verifying
 * the rendering side responds to changing elapsed time.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationHelperRenderTest {

    private fun helper(): NotificationHelper {
        val context: Context = ApplicationProvider.getApplicationContext()
        return NotificationHelper(context).also { it.createChannel() }
    }

    private fun renderedDeltaText(reading: GlucoseReading): String {
        val context: Context = ApplicationProvider.getApplicationContext()
        val notif = helper().buildNotification(
            reading = reading,
            recentReadings = listOf(reading),
            bgLow = 70.0,
            bgHigh = 180.0,
        )
        val rv = notif.bigContentView ?: notif.contentView
            ?: error("notification has no custom content view")
        val mounted = rv.apply(context, FrameLayout(context))
        return mounted.findViewById<TextView>(R.id.tv_delta).text.toString()
    }

    @Test
    fun `since-text shows 0m for a fresh reading`() {
        val now = System.currentTimeMillis()
        val text = renderedDeltaText(reading(ts = now))
        assertTrue("expected '0m' in '$text'", text.contains("0m"))
    }

    @Test
    fun `since-text shows 5m for a five-minute-old reading`() {
        val now = System.currentTimeMillis()
        val text = renderedDeltaText(reading(ts = now - 5L * MS_PER_MINUTE))
        assertTrue("expected '5m' in '$text'", text.contains("5m"))
    }

    @Test
    fun `since-text shows 9m for a nine-minute-old reading`() {
        val now = System.currentTimeMillis()
        val text = renderedDeltaText(reading(ts = now - 9L * MS_PER_MINUTE))
        assertTrue("expected '9m' in '$text'", text.contains("9m"))
    }

    private fun reading(ts: Long): GlucoseReading =
        GlucoseReading(ts = ts, sgv = 100, direction = "Flat", delta = 0.0)

    private companion object {
        const val MS_PER_MINUTE = 60_000L
    }
}
