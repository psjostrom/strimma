package com.psjostrom.strimma.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TreatmentDao {
    @Query("SELECT * FROM treatments WHERE createdAt >= :timestamp ORDER BY createdAt ASC")
    fun since(timestamp: Long): Flow<List<Treatment>>

    @Query("SELECT * FROM treatments WHERE createdAt >= :timestamp ORDER BY createdAt DESC")
    suspend fun allSince(timestamp: Long): List<Treatment>

    @Query("SELECT * FROM treatments WHERE insulin IS NOT NULL AND createdAt >= :timestamp ORDER BY createdAt ASC")
    suspend fun insulinSince(timestamp: Long): List<Treatment>

    @Query(
        "SELECT * FROM treatments WHERE carbs IS NOT NULL AND carbs > 0 " +
            "AND createdAt >= :start AND createdAt <= :end ORDER BY createdAt ASC"
    )
    suspend fun carbsInRange(start: Long, end: Long): List<Treatment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(treatments: List<Treatment>)

    @Query("DELETE FROM treatments WHERE createdAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)
}
