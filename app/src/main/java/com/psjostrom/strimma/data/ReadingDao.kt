package com.psjostrom.strimma.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Suppress("TooManyFunctions") // Room DAO — each query is a distinct data access need
@Dao
interface ReadingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: GlucoseReading)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBatch(readings: List<GlucoseReading>)

    /**
     * Atomic in-bucket replacement used by [com.psjostrom.strimma.service.ReadingPipeline]
     * when an Eversense-style cluster's late value supersedes an earlier stale repost.
     * Concurrent readers (notification update, UI Flow, stale-check loop) never observe
     * the bucket as empty between the delete and the insert.
     */
    @Transaction
    suspend fun replaceInBucket(oldTs: Long, newReading: GlucoseReading) {
        if (oldTs != newReading.ts) deleteByTs(oldTs)
        insert(newReading)
    }

    @Query("SELECT * FROM readings ORDER BY ts DESC LIMIT 1")
    fun latest(): Flow<GlucoseReading?>

    @Query("SELECT MIN(ts) FROM readings")
    suspend fun earliestTs(): Long?

    // Reactive variant — re-emits when the readings table changes (e.g. after a
    // pull from Nightscout backfills older history). The Story screen subscribes
    // so the back-arrow boundary stays in sync without re-opening the screen.
    @Query("SELECT MIN(ts) FROM readings")
    fun earliestTsFlow(): Flow<Long?>

    @Query("SELECT * FROM readings WHERE ts >= :since ORDER BY ts ASC")
    suspend fun since(since: Long): List<GlucoseReading>

    @Query("SELECT * FROM readings WHERE ts >= :since ORDER BY ts ASC")
    fun sinceLive(since: Long): Flow<List<GlucoseReading>>

    @Query("SELECT * FROM readings ORDER BY ts DESC LIMIT :n")
    suspend fun lastN(n: Int): List<GlucoseReading>

    @Query("SELECT * FROM readings ORDER BY ts DESC LIMIT 1")
    suspend fun latestOnce(): GlucoseReading?

    // DESC so backlog drains newest-first during recovery from a long NS outage.
    // Fresh readings push via NightscoutPusher.pushReading inline (separate path
    // from this batch query); the ordering here only governs catch-up order.
    @Query("SELECT * FROM readings WHERE pushed = 0 ORDER BY ts DESC LIMIT :limit")
    suspend fun unpushed(limit: Int = 100): List<GlucoseReading>

    // Bounds the worst case where a poison row at the head of the unpushed queue
    // keeps failing every batch and starves all newer readings. The retention
    // loop runs this unconditionally on a 30-day cutoff regardless of the user's
    // RetentionPolicy — `pushed = 0` rows older than that are degenerate state,
    // not "history the user might want".
    @Query("DELETE FROM readings WHERE pushed = 0 AND ts < :before")
    suspend fun pruneUnpushedBefore(before: Long)

    @Query("UPDATE readings SET pushed = 1 WHERE ts IN (:timestamps)")
    suspend fun markPushed(timestamps: List<Long>)

    @Query("DELETE FROM readings WHERE ts < :before")
    suspend fun pruneBefore(before: Long)

    @Query("DELETE FROM readings WHERE ts = :ts")
    suspend fun deleteByTs(ts: Long)

    @Query("SELECT * FROM readings WHERE ts >= :start AND ts <= :end ORDER BY ts ASC")
    suspend fun readingsInRange(start: Long, end: Long): List<GlucoseReading>
}
