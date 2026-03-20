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

    fun format(mmol: Double): String = when (this) {
        MMOL -> "%.1f".format(mmol)
        MGDL -> "%.0f".format(mmol * MGDL_FACTOR)
    }

    fun formatWithUnit(mmol: Double): String = "${format(mmol)} $label"

    fun formatDelta(deltaMmol: Double): String {
        val sign = if (deltaMmol >= 0) "+" else ""
        return when (this) {
            MMOL -> "$sign%.1f $shortLabel".format(deltaMmol)
            MGDL -> "$sign%.0f $shortLabel".format(deltaMmol * MGDL_FACTOR)
        }
    }

    fun formatDeltaCompact(deltaMmol: Double): String {
        val sign = if (deltaMmol >= 0) "+" else ""
        return when (this) {
            MMOL -> "$sign%.1f".format(deltaMmol)
            MGDL -> "$sign%.0f".format(deltaMmol * MGDL_FACTOR)
        }
    }

    fun formatThreshold(mmol: Float): String = when (this) {
        MMOL -> "%.1f".format(mmol)
        MGDL -> "%.0f".format(mmol * MGDL_FACTOR)
    }

    fun parseThreshold(text: String): Float? {
        val value = text.replace(",", ".").toFloatOrNull() ?: return null
        return when (this) {
            MMOL -> value
            MGDL -> (value / MGDL_FACTOR).toFloat()
        }
    }

    fun displayValue(mmol: Double): Double = when (this) {
        MMOL -> mmol
        MGDL -> mmol * MGDL_FACTOR
    }

    companion object {
        const val MGDL_FACTOR = 18.0182
    }
}
