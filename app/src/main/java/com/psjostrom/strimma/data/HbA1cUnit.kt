package com.psjostrom.strimma.data

enum class HbA1cUnit {
    MMOL_MOL, PERCENT;

    val label: String get() = when (this) {
        PERCENT -> "%"
        MMOL_MOL -> "mmol/mol"
    }

    fun format(dcctPercent: Double): String = when (this) {
        PERCENT -> "%.1f%%".format(dcctPercent)
        MMOL_MOL -> "%.0f mmol/mol".format(toIfcc(dcctPercent))
    }

    companion object {
        private const val IFCC_SLOPE = 10.929
        private const val IFCC_OFFSET = 2.15

        fun toIfcc(dcctPercent: Double): Double = (dcctPercent - IFCC_OFFSET) * IFCC_SLOPE
    }
}
