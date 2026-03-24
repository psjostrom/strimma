package com.psjostrom.strimma.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: GlucoseReading)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBatch(readings: List<GlucoseReading>)

    @Query("SELECT * FROM readings ORDER BY ts DESC LIMIT 1")
    fun latest(): Flow<GlucoseReading?>

    @Query("SELECT * FROM readings WHERE ts >= :since ORDER BY ts ASC")
    suspend fun since(since: Long): List<GlucoseReading>

    @Query("SELECT * FROM readings WHERE ts >= :since ORDER BY ts ASC")
    fun sinceLive(since: Long): Flow<List<GlucoseReading>>

    @Query("SELECT * FROM readings ORDER BY ts DESC LIMIT :n")
    suspend fun lastN(n: Int): List<GlucoseReading>

    @Query("SELECT * FROM readings ORDER BY ts DESC LIMIT 1")
    suspend fun latestOnce(): GlucoseReading?

    @Query("SELECT * FROM readings WHERE pushed = 0 ORDER BY ts ASC LIMIT :limit")
    suspend fun unpushed(limit: Int = 100): List<GlucoseReading>

    @Query("UPDATE readings SET pushed = 1 WHERE ts IN (:timestamps)")
    suspend fun markPushed(timestamps: List<Long>)

    @Query("DELETE FROM readings WHERE ts < :before")
    suspend fun pruneBefore(before: Long)

    @Query("SELECT * FROM readings WHERE ts >= :start AND ts <= :end ORDER BY ts ASC")
    suspend fun readingsInRange(start: Long, end: Long): List<GlucoseReading>
}
