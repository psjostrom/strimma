package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.MS_PER_DAY
import com.psjostrom.strimma.network.TreatmentSyncer.Companion.FULL_LOOKBACK_DAYS
import com.psjostrom.strimma.network.TreatmentSyncer.Companion.FULL_LOOKBACK_MS
import com.psjostrom.strimma.network.TreatmentSyncer.Companion.POLL_LOOKBACK_MS
import com.psjostrom.strimma.network.TreatmentSyncer.Companion.TREATMENTS_PER_DAY
import com.psjostrom.strimma.network.TreatmentSyncer.Companion.computeStartupAction
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
    fun `3-day gap computes correct since and count`() {
        val gapMs = 3 * MS_PER_DAY
        val lastFetch = now - gapMs
        val action = computeStartupAction(lastFetch, now) as StartupAction.DeltaSync

        assertEquals(lastFetch, action.since)
        // (3 * MS_PER_DAY / MS_PER_DAY + 1) = 4 days worth
        assertEquals(4 * TREATMENTS_PER_DAY, action.count)
    }

    @Test
    fun `7-day gap computes correct since and count`() {
        val gapMs = 7 * MS_PER_DAY
        val lastFetch = now - gapMs
        val action = computeStartupAction(lastFetch, now) as StartupAction.DeltaSync

        assertEquals(lastFetch, action.since)
        assertEquals(8 * TREATMENTS_PER_DAY, action.count)
    }

    @Test
    fun `partial day gap rounds up`() {
        val gapMs = 2 * MS_PER_DAY + 1 // 2 days + 1ms
        val lastFetch = now - gapMs
        val action = computeStartupAction(lastFetch, now) as StartupAction.DeltaSync

        assertEquals(lastFetch, action.since)
        // (2 + 1) = 3 days worth
        assertEquals(3 * TREATMENTS_PER_DAY, action.count)
    }

    @Test
    fun `gap exceeding 30 days caps at full lookback`() {
        val lastFetch = now - 45L * MS_PER_DAY // 45 days ago
        val action = computeStartupAction(lastFetch, now) as StartupAction.DeltaSync

        // since capped at 30 days ago, not 45
        assertEquals(now - FULL_LOOKBACK_MS, action.since)
        // gapDays capped at FULL_LOOKBACK_DAYS
        assertEquals(FULL_LOOKBACK_DAYS * TREATMENTS_PER_DAY, action.count)
    }

    @Test
    fun `future lastFetch coerces gap to zero and returns PollSync`() {
        val lastFetch = now + 60_000L // 1 min in the future (clock skew)
        val action = computeStartupAction(lastFetch, now)
        assertEquals(StartupAction.PollSync, action)
    }

    @Test
    fun `gap exactly 30 days returns DeltaSync capped at 30 days`() {
        val lastFetch = now - FULL_LOOKBACK_MS
        val action = computeStartupAction(lastFetch, now) as StartupAction.DeltaSync

        assertEquals(lastFetch, action.since)
        // (30 + 1) coerced to 30
        assertEquals(FULL_LOOKBACK_DAYS * TREATMENTS_PER_DAY, action.count)
    }
}
