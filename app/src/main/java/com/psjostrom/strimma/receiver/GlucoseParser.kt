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
 * Integer values 40+ are treated as mg/dL directly (sensors show "LO" below 40, not a number).
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

    // mg/dL: integer values >= 40 (sensors show "LO" below 40)
    val mgdlMatch = Regex("^(\\d{2,3})$").find(cleaned)
    if (mgdlMatch != null) {
        val mgdl = mgdlMatch.groupValues[1].toIntOrNull() ?: return null
        if (mgdl in MIN_MGDL_RANGE..MAX_MGDL_RANGE) return mgdl.toDouble()
    }

    return null
}

/**
 * Per-package set of TextView strings whose presence in a CGM notification
 * means the displayed glucose value is stale and must not be ingested.
 *
 * Why this exists separately from `notification.when`:
 *   `notification.when` (set on the original Notification, preserved across
 *   listener rebinds) tells us when the notification was last *posted*. CamAPS
 *   in "Attempting" mode keeps reposting the persistent notification every
 *   minute with a fresh `when` but with the LAST KNOWN BG value, not a new
 *   sensor reading. So `when` is recent — only the number is stale. The status
 *   word in the notification body is the only signal that distinguishes this
 *   case from a real reading.
 *
 * Why not the xDrip "jam counter" (reject after N consecutive identical reads):
 *   stable BG is normal — overnight, between meals, post-correction plateau —
 *   and produces 5+ identical readings in a row legitimately. xDrip's own
 *   community has documented the false-positive problem this causes for CamAPS
 *   users (https://github.com/NightscoutFoundation/xDrip/discussions/3108).
 *
 * Match strategy:
 *   - Per-package set, indexed by `sbn.packageName`. Only packages where we have
 *     direct evidence of the failure mode get an entry.
 *   - Exact, case-sensitive equality against an entire TextView. Status words
 *     live in their own TextView slot in CamAPS's RemoteViews, so substring
 *     matching would over-trigger without buying anything. Case is stable
 *     across all observed logs.
 *   - Unknown package returns false (no filter) — we never speculatively reject
 *     readings from apps whose notification shape we haven't observed.
 *
 * Currently configured packages:
 *   - All five CamDiab variants (mmol/L, mg/dL, HX-pump mmol/L & mg/dL, Canada).
 *     Same app, same UI, same status words across locales/bundles.
 *
 * To extend:
 *   1. Capture a debug log showing the failure mode (e.g. `[App, ..., Status,
 *      <stale value>, ...]`).
 *   2. Add `<package>` -> `setOf("<status word>")` to the map below.
 *   3. Add a regression test in StaleStatusFilterTest using the captured
 *      TextView list as a fixture.
 */
private val STALE_STATUS_KEYWORDS_BY_PACKAGE: Map<String, Set<String>> = mapOf(
    "com.camdiab.fx_alert.mmoll" to setOf("Attempting"),
    "com.camdiab.fx_alert.mgdl" to setOf("Attempting"),
    "com.camdiab.fx_alert.hx.mmoll" to setOf("Attempting"),
    "com.camdiab.fx_alert.hx.mgdl" to setOf("Attempting"),
    "com.camdiab.fx_alert.mmoll.ca" to setOf("Attempting"),
)

/**
 * Returns true when [textViews] contains a status word that, for [packageName],
 * means the displayed glucose value is stale. See `STALE_STATUS_KEYWORDS_BY_PACKAGE`
 * for the full rationale and the per-package keyword sets.
 */
fun isStaleStatusNotification(textViews: List<String>, packageName: String?): Boolean {
    val keywords = STALE_STATUS_KEYWORDS_BY_PACKAGE[packageName] ?: return false
    return textViews.any { it in keywords }
}
