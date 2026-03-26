package com.psjostrom.strimma.network

import org.junit.Assert.*
import org.junit.Test

class PushFailureTrackerTest {

    private val fifteenMinutes = 15 * 60 * 1000L

    @Test
    fun `first failure sets timestamp but does not fire alert`() {
        var alertFired = false
        val tracker = PushFailureTracker(
            alertThresholdMs = fifteenMinutes,
            onAlertChanged = { alertFired = it },
            clock = { 1_000_000L }
        )

        tracker.onFailure()

        assertFalse(alertFired)
    }

    @Test
    fun `failures within threshold do not fire alert`() {
        var alertFired = false
        var time = 1_000_000L
        val tracker = PushFailureTracker(
            alertThresholdMs = fifteenMinutes,
            onAlertChanged = { alertFired = it },
            clock = { time }
        )

        tracker.onFailure()
        time += fifteenMinutes - 1
        tracker.onFailure()

        assertFalse(alertFired)
    }

    @Test
    fun `alert fires after threshold of consecutive failures`() {
        var alertFired = false
        var time = 1_000_000L
        val tracker = PushFailureTracker(
            alertThresholdMs = fifteenMinutes,
            onAlertChanged = { alertFired = it },
            clock = { time }
        )

        tracker.onFailure()
        time += fifteenMinutes
        tracker.onFailure()

        assertTrue(alertFired)
    }

    @Test
    fun `success resets timestamp and cancels alert`() {
        val alerts = mutableListOf<Boolean>()
        var time = 1_000_000L
        val tracker = PushFailureTracker(
            alertThresholdMs = fifteenMinutes,
            onAlertChanged = { alerts.add(it) },
            clock = { time }
        )

        tracker.onFailure()
        time += fifteenMinutes
        tracker.onFailure()
        assertTrue(alerts.last())

        tracker.onSuccess()
        assertFalse(alerts.last())
    }

    @Test
    fun `failure after success restarts the window`() {
        var alertFired = false
        var time = 1_000_000L
        val tracker = PushFailureTracker(
            alertThresholdMs = fifteenMinutes,
            onAlertChanged = { alertFired = it },
            clock = { time }
        )

        // Fail for 10 minutes
        tracker.onFailure()
        time += 10 * 60 * 1000L

        // Success resets
        tracker.onSuccess()
        alertFired = false

        // New failure starts fresh window
        time += 1000L
        tracker.onFailure()
        time += fifteenMinutes - 1
        tracker.onFailure()

        // Still within new window — no alert
        assertFalse(alertFired)

        // Cross the threshold from new start
        time += 1
        tracker.onFailure()
        assertTrue(alertFired)
    }
}
