package com.psjostrom.strimma.ui

import androidx.compose.ui.geometry.Offset

internal class DoubleTapDetector(
    private val doubleTapTimeoutMillis: Long = 300L,
    private val doubleTapMinTimeMillis: Long = 40L,
    private val doubleTapSlop: Float = 100f
) {
    private var hasPriorTap = false
    private var lastTapTime = 0L
    private var lastTapPosition = Offset.Zero

    fun onSingleTap(timeMs: Long, position: Offset): Boolean {
        val dt = timeMs - lastTapTime
        val dist = (position - lastTapPosition).getDistance()
        val isDoubleTap = hasPriorTap && dt in doubleTapMinTimeMillis..doubleTapTimeoutMillis && dist <= doubleTapSlop
        if (isDoubleTap) {
            reset()
            return true
        } else {
            hasPriorTap = true
            lastTapTime = timeMs
            lastTapPosition = position
            return false
        }
    }

    fun reset() {
        hasPriorTap = false
        lastTapTime = 0L
        lastTapPosition = Offset.Zero
    }
}
