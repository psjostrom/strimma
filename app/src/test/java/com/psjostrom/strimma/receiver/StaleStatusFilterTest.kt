package com.psjostrom.strimma.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CAMAPS_MMOLL = "com.camdiab.fx_alert.mmoll"
private const val CAMAPS_MGDL = "com.camdiab.fx_alert.mgdl"
private const val CAMAPS_HX_MMOLL = "com.camdiab.fx_alert.hx.mmoll"
private const val CAMAPS_HX_MGDL = "com.camdiab.fx_alert.hx.mgdl"
private const val CAMAPS_CA = "com.camdiab.fx_alert.mmoll.ca"
private const val DEXCOM_G6 = "com.dexcom.g6"

/**
 * Filter rationale and references in the implementation's KDoc. These tests use
 * real TextView lists captured from `strimma-2026-05-11.log` so the parser can't
 * silently regress against the actual CamAPS notification shape.
 *
 * `notification.when` (handled separately in PR #219) and this stale-status filter
 * cover two independent failure modes:
 *  - `notification.when` fix: rebind redelivery of an unchanged notification —
 *    the same StatusBarNotification is preserved, so the original `when` is
 *    intact and `minutesAgo` grows correctly past the rebind moment.
 *  - This filter: CamAPS in "Attempting" mode posts FRESH notifications (with
 *    fresh `when`) every minute that re-display the last-known stale value.
 *    `notification.when` cannot help here because `when` is genuinely fresh —
 *    only the BG number is stale. The status word in the notification body is
 *    the only signal that distinguishes this case from a real reading.
 */
class StaleStatusFilterTest {

    // Captured verbatim from strimma-2026-05-11.log line 3790 — sensor went
    // offline, CamAPS switched to "Attempting" and kept reposting 5,9 (the last
    // known reading from before the disconnect) every minute. Without this
    // filter, every repost was stored as a fresh reading.
    @Test
    fun `rejects CamAPS Attempting notification with stale value`() {
        val texts = listOf("CamAPS FX", "Auto mode", "Attempting", "5,9", "mmol/L")
        assertTrue(isStaleStatusNotification(texts, CAMAPS_MMOLL))
    }

    // Same Attempting state, no value. Captured from log line 3786. The parser
    // would have rejected `---` anyway via tryParseGlucose, but this guard runs
    // BEFORE parsing — keeps the Attempting case symmetric (both rejected the
    // same way) and saves a parse pass.
    @Test
    fun `rejects CamAPS Attempting notification with no value`() {
        val texts = listOf("CamAPS FX", "Auto mode", "Attempting", "---", "mmol/L")
        assertTrue(isStaleStatusNotification(texts, CAMAPS_MMOLL))
    }

    // Captured from log lines 1-3779 (every normal "On" entry). This is the
    // happy path — sensor is reading, value is real. Filter must NOT trigger.
    @Test
    fun `accepts CamAPS On notification with fresh value`() {
        val texts = listOf("CamAPS FX", "Auto mode", "On", "5,9", "mmol/L")
        assertFalse(isStaleStatusNotification(texts, CAMAPS_MMOLL))
    }

    @Test
    fun `accepts CamAPS On notification across the BG range`() {
        // Spot-check several values seen in the log (spanning hypo, normal, hyper).
        listOf("3,9", "5,5", "7,1", "10,2", "13,5").forEach { value ->
            val texts = listOf("CamAPS FX", "Auto mode", "On", value, "mmol/L")
            assertFalse(
                "$value should pass through when status is On",
                isStaleStatusNotification(texts, CAMAPS_MMOLL),
            )
        }
    }

    @Test
    fun `applies to all CamAPS package variants`() {
        // CamDiab ships the same UI across mmol/mg-dL/HX/Canada bundles. We have
        // logs only from `mmoll` but the others are the same app, so the filter
        // must catch them too — otherwise a Canadian or HX-pump user hits the
        // bug that mmoll-locale users don't.
        val staleTexts = listOf("CamAPS FX", "Auto mode", "Attempting", "5,9", "mmol/L")
        listOf(CAMAPS_MMOLL, CAMAPS_MGDL, CAMAPS_HX_MMOLL, CAMAPS_HX_MGDL, CAMAPS_CA).forEach { pkg ->
            assertTrue("$pkg should reject Attempting", isStaleStatusNotification(staleTexts, pkg))
        }
    }

    @Test
    fun `does not match keyword as substring of another text`() {
        // Defensive: equality (not contains) means "Attempting reconnect..." would
        // NOT be caught here. We accept that risk to avoid over-triggering on
        // unrelated text — extend the keyword set when we observe the variant.
        val texts = listOf("CamAPS FX", "Auto mode", "Attempting reconnect", "5,9", "mmol/L")
        assertFalse(isStaleStatusNotification(texts, CAMAPS_MMOLL))
    }

    @Test
    fun `match is case-sensitive`() {
        // CamAPS uses literal "Attempting" — case is stable across our logs.
        // Accepting case variants would risk over-matching legit text in other
        // apps' notifications if we extend the package list later.
        val texts = listOf("CamAPS FX", "Auto mode", "attempting", "5,9", "mmol/L")
        assertFalse(isStaleStatusNotification(texts, CAMAPS_MMOLL))
    }

    @Test
    fun `unknown package gets no filtering`() {
        // We have no logs for non-CamAPS packages. Keeping the default behaviour
        // (no filter) means we don't speculatively reject readings from apps
        // whose notification shape we haven't seen. Add per-package entries when
        // logs justify it.
        val texts = listOf("Some App", "Attempting", "5,9")
        assertFalse(isStaleStatusNotification(texts, DEXCOM_G6))
    }

    @Test
    fun `null package gets no filtering`() {
        // Defensive: the listener always passes a non-null packageName, but the
        // pure function should not crash if a future caller ever passes null.
        val texts = listOf("Anything", "Attempting", "5,9")
        assertFalse(isStaleStatusNotification(texts, null))
    }

    @Test
    fun `empty text list never matches`() {
        assertFalse(isStaleStatusNotification(emptyList(), CAMAPS_MMOLL))
    }
}
