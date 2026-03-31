@file:Suppress("MatchingDeclarationName")

package com.psjostrom.strimma.graph

const val CRITICAL_LOW = 54.0
const val CRITICAL_HIGH = 234.0

// Android Canvas colors (ARGB int) — keep in sync with Color.kt status colors
const val CANVAS_IN_RANGE = 0xFF56CCF2.toInt()
const val CANVAS_HIGH = 0xFFFFB800.toInt()
const val CANVAS_DANGER = 0xFFFF4D6A.toInt()

// Treatment marker colors — keep in sync with Color.kt (BolusBlue, CarbGreen)
const val CANVAS_BOLUS = 0xFF5B8DEF.toInt()
const val CANVAS_CARB = 0xFF4CAF50.toInt()

// Exercise band color — keep in sync with Color.kt (ExerciseDefault)
const val CANVAS_EXERCISE = 0xFF8B8BBA.toInt()

enum class BgStatus { IN_RANGE, HIGH, DANGER }

fun bgStatusFor(mgdl: Double, bgLow: Double, bgHigh: Double): BgStatus = when {
    mgdl <= CRITICAL_LOW -> BgStatus.DANGER
    mgdl < bgLow -> BgStatus.DANGER
    mgdl >= CRITICAL_HIGH -> BgStatus.DANGER
    mgdl > bgHigh -> BgStatus.HIGH
    else -> BgStatus.IN_RANGE
}

fun canvasColorFor(mgdl: Double, bgLow: Double, bgHigh: Double): Int =
    when (bgStatusFor(mgdl, bgLow, bgHigh)) {
        BgStatus.IN_RANGE -> CANVAS_IN_RANGE
        BgStatus.HIGH -> CANVAS_HIGH
        BgStatus.DANGER -> CANVAS_DANGER
    }

data class YRange(val yMin: Double, val yMax: Double) {
    val range: Double get() = yMax - yMin
}

private const val Y_PADDING_LARGE = 9.0
private const val Y_PADDING_SMALL = 5.0

private const val Y_RANGE_THRESHOLD = 180.0
private const val MGDL_Y_STEP_LARGE = 50.0
private const val MGDL_Y_STEP_SMALL = 25.0
private const val MMOL_Y_STEP_LARGE = 2.0
private const val MMOL_Y_STEP_SMALL = 1.0

data class YAxisLabel(val mgdl: Double, val text: String)

fun computeYAxisLabels(yr: YRange, glucoseUnit: com.psjostrom.strimma.data.GlucoseUnit): List<YAxisLabel> {
    val yStep = if (glucoseUnit == com.psjostrom.strimma.data.GlucoseUnit.MGDL) {
        if (yr.range > Y_RANGE_THRESHOLD) MGDL_Y_STEP_LARGE else MGDL_Y_STEP_SMALL
    } else {
        (if (yr.range > Y_RANGE_THRESHOLD) MMOL_Y_STEP_LARGE else MMOL_Y_STEP_SMALL) *
            com.psjostrom.strimma.data.GlucoseUnit.MGDL_FACTOR
    }
    val labels = mutableListOf<YAxisLabel>()
    var yLabel = Math.ceil(yr.yMin / yStep) * yStep
    while (yLabel <= yr.yMax) {
        val text = if (glucoseUnit == com.psjostrom.strimma.data.GlucoseUnit.MGDL) {
            "%.0f".format(yLabel)
        } else {
            "%.0f".format(yLabel / com.psjostrom.strimma.data.GlucoseUnit.MGDL_FACTOR)
        }
        labels += YAxisLabel(yLabel, text)
        yLabel += yStep
    }
    return labels
}

fun computeYRange(mgdlValues: List<Double>, bgLow: Double, bgHigh: Double): YRange {
    val dataMin = mgdlValues.minOrNull() ?: bgLow
    val dataMax = mgdlValues.maxOrNull() ?: bgHigh
    return YRange(
        yMin = minOf(bgLow - Y_PADDING_LARGE, CRITICAL_LOW - Y_PADDING_SMALL, dataMin - Y_PADDING_SMALL),
        yMax = maxOf(bgHigh + Y_PADDING_LARGE, CRITICAL_HIGH + Y_PADDING_SMALL, dataMax + Y_PADDING_SMALL)
    )
}
