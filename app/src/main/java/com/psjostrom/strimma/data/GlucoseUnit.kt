package com.psjostrom.strimma.data

enum class GlucoseUnit {
    MMOL, MGDL;

    val label: String get() = when (this) {
        MMOL -> "mmol/L"
        MGDL -> "mg/dL"
    }

    val shortLabel: String get() = when (this) {
        MMOL -> "mmol/l"
        MGDL -> "mg/dl"
    }

    fun format(mgdl: Double): String = when (this) {
        MMOL -> "%.1f".format(mgdl / MGDL_FACTOR)
        MGDL -> "%.0f".format(mgdl)
    }

    fun format(sgv: Int): String = format(sgv.toDouble())

    fun formatWithUnit(mgdl: Double): String = "${format(mgdl)} $label"

    fun formatDelta(deltaMgdl: Double): String {
        val sign = if (deltaMgdl >= 0) "+" else ""
        return when (this) {
            MMOL -> "$sign%.1f $shortLabel".format(deltaMgdl / MGDL_FACTOR)
            MGDL -> "$sign%.0f $shortLabel".format(deltaMgdl)
        }
    }

    fun formatDeltaCompact(deltaMgdl: Double): String {
        val sign = if (deltaMgdl >= 0) "+" else ""
        return when (this) {
            MMOL -> "$sign%.1f".format(deltaMgdl / MGDL_FACTOR)
            MGDL -> "$sign%.0f".format(deltaMgdl)
        }
    }

    fun formatThreshold(mgdl: Float): String = when (this) {
        MMOL -> "%.1f".format(mgdl / MGDL_FACTOR)
        MGDL -> "%.0f".format(mgdl)
    }

    fun parseThreshold(text: String): Float? {
        val value = text.replace(",", ".").toFloatOrNull() ?: return null
        return when (this) {
            MMOL -> (value * MGDL_FACTOR).toFloat()
            MGDL -> value
        }
    }

    fun displayValue(mgdl: Double): Double = when (this) {
        MMOL -> mgdl / MGDL_FACTOR
        MGDL -> mgdl
    }

    companion object {
        const val MGDL_FACTOR = 18.0182
    }
}
