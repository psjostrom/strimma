package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.MS_PER_DAY
import com.psjostrom.strimma.network.TreatmentSyncer.Companion.FULL_LOOKBACK_MS
import com.psjostrom.strimma.network.TreatmentSyncer.Companion.POLL_LOOKBACK_MS
import com.psjostrom.strimma.network.TreatmentSyncer.Companion.computeStartupAction
import com.psjostrom.strimma.network.TreatmentSyncer.Companion.sanitizeErrorMessage
import com.psjostrom.strimma.network.TreatmentSyncer.StartupAction
import org.junit.Assert.*
import org.junit.Test

class TreatmentSyncerTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `null lastFetch returns FullSync`() {
        val action = computeStartupAction(lastFetch = null, now = now)
        assertEquals(StartupAction.FullSync, action)
    }

    @Test
    fun `gap within 24h returns PollSync`() {
        val lastFetch = now - 1 * 60 * 60 * 1000L // 1h ago
        val action = computeStartupAction(lastFetch, now)
        assertEquals(StartupAction.PollSync, action)
    }

    @Test
    fun `gap exactly 24h returns PollSync`() {
        val lastFetch = now - POLL_LOOKBACK_MS
        val action = computeStartupAction(lastFetch, now)
        assertEquals(StartupAction.PollSync, action)
    }

    @Test
    fun `gap just over 24h returns DeltaSync`() {
        val lastFetch = now - POLL_LOOKBACK_MS - 1
        val action = computeStartupAction(lastFetch, now)
        assertTrue(action is StartupAction.DeltaSync)
    }

    @Test
    fun `3-day gap syncs from the last fetch`() {
        val gapMs = 3 * MS_PER_DAY
        val lastFetch = now - gapMs
        val action = computeStartupAction(lastFetch, now) as StartupAction.DeltaSync

        assertEquals(lastFetch, action.since)
    }

    @Test
    fun `7-day gap syncs from the last fetch`() {
        val gapMs = 7 * MS_PER_DAY
        val lastFetch = now - gapMs
        val action = computeStartupAction(lastFetch, now) as StartupAction.DeltaSync

        assertEquals(lastFetch, action.since)
    }

    @Test
    fun `gap exceeding full lookback caps at full lookback`() {
        val lastFetch = now - 150L * MS_PER_DAY // 150 days ago
        val action = computeStartupAction(lastFetch, now) as StartupAction.DeltaSync

        // since capped at the full lookback, not 150 days
        assertEquals(now - FULL_LOOKBACK_MS, action.since)
    }

    @Test
    fun `future lastFetch coerces gap to zero and returns PollSync`() {
        val lastFetch = now + 60_000L // 1 min in the future (clock skew)
        val action = computeStartupAction(lastFetch, now)
        assertEquals(StartupAction.PollSync, action)
    }

    @Test
    fun `gap exactly at full lookback returns DeltaSync from the full lookback`() {
        val lastFetch = now - FULL_LOOKBACK_MS
        val action = computeStartupAction(lastFetch, now) as StartupAction.DeltaSync

        assertEquals(lastFetch, action.since)
    }

    @Test
    fun `sanitizeErrorMessage strips timeout exceptions`() {
        val e = Exception("Connect timeout has expired for request GET https://my-ns.example.com/api/v1/treatments.json")
        assertEquals("Connection timeout", sanitizeErrorMessage(e))
    }

    @Test
    fun `sanitizeErrorMessage strips connection exceptions`() {
        val e = Exception("Failed to connect to my-ns.example.com/1.2.3.4:443")
        assertEquals("Cannot connect", sanitizeErrorMessage(e))
    }

    @Test
    fun `sanitizeErrorMessage strips SSL exceptions`() {
        val e = Exception("SSL handshake failed with https://my-ns.example.com")
        assertEquals("SSL error", sanitizeErrorMessage(e))
    }

    @Test
    fun `sanitizeErrorMessage strips certificate exceptions`() {
        val e = Exception("Certificate validation failed for my-ns.example.com")
        assertEquals("SSL error", sanitizeErrorMessage(e))
    }

    @Test
    fun `sanitizeErrorMessage strips refused exceptions`() {
        val e = Exception("Connection refused: my-ns.example.com:443")
        assertEquals("Connection refused", sanitizeErrorMessage(e))
    }

    @Test
    fun `sanitizeErrorMessage falls back to generic message`() {
        val e = Exception("some unknown error from https://my-ns.example.com")
        assertEquals("Sync failed", sanitizeErrorMessage(e))
    }

    @Test
    fun `sanitizeErrorMessage handles null message`() {
        val e = Exception(null as String?)
        assertEquals("Sync failed", sanitizeErrorMessage(e))
    }
}
