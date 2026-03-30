@file:Suppress("MatchingDeclarationName")

package com.psjostrom.strimma.graph

const val CRITICAL_LOW = 54.0
const val CRITICAL_HIGH = 234.0

// Android Canvas colors (ARGB int) — keep in sync with Color.kt status colors
const val CANVAS_IN_RANGE = 0xFF56CCF2.toInt()
const val CANVAS_HIGH = 0xFFFFB800.toInt()
const val CANVAS_LOW = 0xFFFF4D6A.toInt()

// Treatment marker colors — keep in sync with Color.kt (BolusBlue, CarbGreen)
const val CANVAS_BOLUS = 0xFF5B8DEF.toInt()
const val CANVAS_CARB = 0xFF4CAF50.toInt()

// Exercise band color — keep in sync with Color.kt (ExerciseDefault)
const val CANVAS_EXERCISE = 0xFF8B8BBA.toInt()

enum class BgStatus { IN_RANGE, HIGH, LOW }

fun bgStatusFor(mgdl: Double, bgLow: Double, bgHigh: Double): BgStatus = when {
    mgdl <= CRITICAL_LOW -> BgStatus.LOW
    mgdl < bgLow -> BgStatus.LOW
    mgdl >= CRITICAL_HIGH -> BgStatus.LOW
    mgdl > bgHigh -> BgStatus.HIGH
    else -> BgStatus.IN_RANGE
}

fun canvasColorFor(mgdl: Double, bgLow: Double, bgHigh: Double): Int =
    when (bgStatusFor(mgdl, bgLow, bgHigh)) {
        BgStatus.IN_RANGE -> CANVAS_IN_RANGE
        BgStatus.HIGH -> CANVAS_HIGH
        BgStatus.LOW -> CANVAS_LOW
    }

data class YRange(val yMin: Double, val yMax: Double) {
    val range: Double get() = yMax - yMin
}

private const val Y_PADDING_LARGE = 9.0
private const val Y_PADDING_SMALL = 5.0

fun computeYRange(mgdlValues: List<Double>, bgLow: Double, bgHigh: Double): YRange {
    val dataMin = mgdlValues.minOrNull() ?: bgLow
    val dataMax = mgdlValues.maxOrNull() ?: bgHigh
    return YRange(
        yMin = minOf(bgLow - Y_PADDING_LARGE, CRITICAL_LOW - Y_PADDING_SMALL, dataMin - Y_PADDING_SMALL),
        yMax = maxOf(bgHigh + Y_PADDING_LARGE, CRITICAL_HIGH + Y_PADDING_SMALL, dataMax + Y_PADDING_SMALL)
    )
}
