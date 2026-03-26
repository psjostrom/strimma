package com.psjostrom.strimma.network

import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks consecutive push failures and fires an alert after [alertThresholdMs] of uninterrupted failures.
 * Thread-safe via AtomicLong.
 */
class PushFailureTracker(
    private val alertThresholdMs: Long,
    private val onAlertChanged: (firing: Boolean) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val firstFailureTs = AtomicLong(0L)

    fun onFailure() {
        val now = clock()
        firstFailureTs.compareAndSet(0L, now)
        val start = firstFailureTs.get()
        if (start != 0L && now - start >= alertThresholdMs) {
            onAlertChanged(true)
        }
    }

    fun onSuccess() {
        firstFailureTs.set(0L)
        onAlertChanged(false)
    }
}
