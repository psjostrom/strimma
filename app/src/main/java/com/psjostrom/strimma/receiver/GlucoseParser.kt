package com.psjostrom.strimma.receiver

/**
 * Parses a glucose value from notification text.
 * Handles comma/dot decimals, unit suffixes, Unicode arrows/symbols, and non-breaking spaces.
 * Returns mmol/L as Double, or null if the text doesn't contain a valid glucose value.
 */
fun tryParseGlucose(raw: String): Double? {
    val cleaned = raw
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

    if (cleaned.isBlank()) return null

    // Match patterns like "13,5" or "13.5" or "7,8" — must have decimal
    val match = Regex("^(\\d{1,2})[.,](\\d)$").find(cleaned) ?: return null
    val whole = match.groupValues[1]
    val decimal = match.groupValues[2]
    return "$whole.$decimal".toDoubleOrNull()
}
