package com.psjostrom.strimma.graph

const val CRITICAL_LOW = 3.0
const val CRITICAL_HIGH = 13.0

// Android Canvas colors (ARGB int)
const val CANVAS_IN_RANGE = 0xFF56CCF2.toInt()
const val CANVAS_HIGH = 0xFFFFBE76.toInt()
const val CANVAS_LOW = 0xFFFF6B6B.toInt()

fun canvasColorFor(mmol: Double, bgLow: Double, bgHigh: Double): Int = when {
    mmol <= CRITICAL_LOW -> CANVAS_LOW
    mmol < bgLow -> CANVAS_LOW
    mmol >= CRITICAL_HIGH -> CANVAS_LOW
    mmol > bgHigh -> CANVAS_HIGH
    else -> CANVAS_IN_RANGE
}

data class YRange(val yMin: Double, val yMax: Double) {
    val range: Double get() = yMax - yMin
}

fun computeYRange(mmolValues: List<Double>, bgLow: Double, bgHigh: Double): YRange {
    val dataMin = mmolValues.minOrNull() ?: bgLow
    val dataMax = mmolValues.maxOrNull() ?: bgHigh
    return YRange(
        yMin = minOf(bgLow - 0.5, CRITICAL_LOW - 0.3, dataMin - 0.3),
        yMax = maxOf(bgHigh + 0.5, CRITICAL_HIGH + 0.3, dataMax + 0.3)
    )
}
