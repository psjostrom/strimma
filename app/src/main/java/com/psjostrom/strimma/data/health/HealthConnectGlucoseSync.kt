package com.psjostrom.strimma.data.health

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import kotlinx.coroutines.sync.Mutex

// A follower catch-up or a LibreLinkUp poll stores many readings at once, and the fan-out
// reports only the newest of them. Writing just that reading left the rest out of Health
// Connect. Each write therefore sends readings Health Connect does not have yet.

/** How far back a write looks for readings Health Connect does not have. */
internal const val HC_CATCH_UP_MS = 7L * 24 * 60 * 60 * 1000

/** Health Connect accepts at most 1000 records per insert request. */
internal const val HC_MAX_RECORDS_PER_WRITE = 1000

private val writeMutex = Mutex()

/**
 * Writes the oldest readings from the last [HC_CATCH_UP_MS] that Health Connect does not
 * have, at most one batch per call, through [write], and marks the readings [write]
 * returns. One batch per call keeps the shutdown flush of the fan-out short; a backlog
 * drains over the next readings. A call that finds another write running skips, because
 * the running write sends the same readings.
 */
internal suspend fun writeUnwrittenToHealthConnect(
    dao: ReadingDao,
    now: Long,
    write: suspend (List<GlucoseReading>) -> List<GlucoseReading>,
) {
    if (!writeMutex.tryLock()) return
    try {
        val batch = dao.notWrittenToHealthConnect(from = now - HC_CATCH_UP_MS, limit = HC_MAX_RECORDS_PER_WRITE)
        if (batch.isEmpty()) return
        val written = write(batch)
        if (written.isNotEmpty()) dao.markWrittenToHealthConnect(written.map { it.ts })
    } finally {
        writeMutex.unlock()
    }
}
