package com.psjostrom.strimma.receiver

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkoutAlarmReceiverTest {

    private val receiver = WorkoutAlarmReceiver()

    @Before
    fun reset() {
        WorkoutAlarmReceiver.notificationTriggerFired.set(false)
    }

    @Test
    fun `correct action sets flag to true`() {
        val intent = Intent(WorkoutAlarmReceiver.ACTION)
        receiver.onReceive(ApplicationProvider.getApplicationContext(), intent)
        assertTrue(WorkoutAlarmReceiver.notificationTriggerFired.get())
    }

    @Test
    fun `wrong action does not set flag`() {
        val intent = Intent("com.psjostrom.strimma.WRONG_ACTION")
        receiver.onReceive(ApplicationProvider.getApplicationContext(), intent)
        assertFalse(WorkoutAlarmReceiver.notificationTriggerFired.get())
    }

    @Test
    fun `null intent does not crash or set flag`() {
        receiver.onReceive(ApplicationProvider.getApplicationContext(), null)
        assertFalse(WorkoutAlarmReceiver.notificationTriggerFired.get())
    }

    @Test
    fun `flag survives multiple fires`() {
        val intent = Intent(WorkoutAlarmReceiver.ACTION)
        receiver.onReceive(ApplicationProvider.getApplicationContext(), intent)
        receiver.onReceive(ApplicationProvider.getApplicationContext(), intent)
        assertTrue(WorkoutAlarmReceiver.notificationTriggerFired.get())
    }

    @Test
    fun `flag can be reset externally`() {
        val intent = Intent(WorkoutAlarmReceiver.ACTION)
        receiver.onReceive(ApplicationProvider.getApplicationContext(), intent)
        assertTrue(WorkoutAlarmReceiver.notificationTriggerFired.get())
        WorkoutAlarmReceiver.notificationTriggerFired.set(false)
        assertFalse(WorkoutAlarmReceiver.notificationTriggerFired.get())
    }
}
