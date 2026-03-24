package com.psjostrom.strimma.data.health

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {

    @Query(
        "SELECT * FROM exercise_sessions WHERE startTime <= :end AND endTime >= :start ORDER BY startTime ASC"
    )
    suspend fun getSessionsInRange(start: Long, end: Long): List<StoredExerciseSession>

    @Query("SELECT * FROM exercise_sessions WHERE id = :id")
    suspend fun getSessionById(id: String): StoredExerciseSession?

    @Query("SELECT * FROM heart_rate_samples WHERE sessionId = :sessionId ORDER BY time ASC")
    suspend fun getHeartRateForSession(sessionId: String): List<HeartRateSample>

    @Query("SELECT * FROM exercise_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<StoredExerciseSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: StoredExerciseSession)

    @Query("DELETE FROM heart_rate_samples WHERE sessionId = :sessionId")
    suspend fun deleteHeartRateForSession(sessionId: String)

    @Insert
    suspend fun insertHeartRateSamples(samples: List<HeartRateSample>)

    @Query("DELETE FROM exercise_sessions WHERE endTime < :cutoff")
    suspend fun deleteSessionsOlderThan(cutoff: Long)

    @Transaction
    suspend fun upsertSessionWithHeartRate(
        session: StoredExerciseSession,
        heartRate: List<HeartRateSample>
    ) {
        deleteHeartRateForSession(session.id)
        upsertSession(session)
        insertHeartRateSamples(heartRate)
    }
}
