package com.psjostrom.strimma.receiver

private const val MGDL_CONVERSION = 18.0182
private const val MIN_MGDL_RANGE = 51
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
 * Returns mmol/L as Double, or null if the text doesn't contain a valid glucose value.
 *
 * Supports both mmol/L (e.g. "13.5", "7,8") and mg/dL (e.g. "180", "95").
 * Values with a decimal are treated as mmol/L. Integer values above 50 are treated
 * as mg/dL and converted. Integer values 20-50 are ambiguous and skipped.
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
        return "$whole.$decimal".toDoubleOrNull()
    }

    // mg/dL: integer values > 50 (no overlap with mmol/L range)
    val mgdlMatch = Regex("^(\\d{2,3})$").find(cleaned)
    if (mgdlMatch != null) {
        val mgdl = mgdlMatch.groupValues[1].toIntOrNull() ?: return null
        if (mgdl in MIN_MGDL_RANGE..MAX_MGDL_RANGE) return mgdl / MGDL_CONVERSION
    }

    return null
}
