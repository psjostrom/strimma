package com.psjostrom.strimma.receiver

import com.psjostrom.strimma.data.GlucoseUnit

private val MGDL_CONVERSION = GlucoseUnit.MGDL_FACTOR
private const val MIN_MGDL_RANGE = 40 // Sensors show "LO"/"LOW" below 40, not a number
private const val MAX_MGDL_RANGE = 500

private fun cleanGlucoseText(raw: String): String = raw
    .replace("\u00a0", " ")  // non-breaking space
    .replace("\u2060", "")   // word joiner
    .replace("mmol/L", "")
    .replace("mmol/l", "")
    .replace("mg/dL", "")
    .replace("mg/dl", "")
    .replace("≤", "")
    .replace("≥", "")
    .filterNot { it in '\u2190'..'\u21FF' }  // arrows
    .filterNot { it in '\u2700'..'\u27BF' }  // dingbats
    .filterNot { it in '\u2900'..'\u297F' }  // supplemental arrows
    .filterNot { it in '\u2B00'..'\u2BFF' }  // misc symbols
    .trim()

/**
 * Parses a glucose value from notification text.
 * Handles comma/dot decimals, unit suffixes, Unicode arrows/symbols, and non-breaking spaces.
 * Returns mg/dL as Double, or null if the text doesn't contain a valid glucose value.
 *
 * Supports both mmol/L (e.g. "13.5", "7,8") and mg/dL (e.g. "180", "95").
 * Values with a decimal are treated as mmol/L and converted to mg/dL.
 * Integer values 20+ are treated as mg/dL directly (no CGM app omits the decimal from mmol/L values).
 */
@Suppress("ReturnCount") // Early returns in parser
fun tryParseGlucose(raw: String): Double? {
    val cleaned = cleanGlucoseText(raw)
    if (cleaned.isBlank()) return null

    // mmol/L: patterns like "13,5" or "13.5" or "7,8" — must have decimal
    val mmolMatch = Regex("^(\\d{1,2})[.,](\\d)$").find(cleaned)
    if (mmolMatch != null) {
        val whole = mmolMatch.groupValues[1]
        val decimal = mmolMatch.groupValues[2]
        val mmol = "$whole.$decimal".toDoubleOrNull() ?: return null
        return mmol * MGDL_CONVERSION
    }

    // mg/dL: integer values > 50 (no overlap with mmol/L range)
    val mgdlMatch = Regex("^(\\d{2,3})$").find(cleaned)
    if (mgdlMatch != null) {
        val mgdl = mgdlMatch.groupValues[1].toIntOrNull() ?: return null
        if (mgdl in MIN_MGDL_RANGE..MAX_MGDL_RANGE) return mgdl.toDouble()
    }

    return null
}
