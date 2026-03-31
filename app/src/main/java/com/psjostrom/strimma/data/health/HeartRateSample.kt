package com.psjostrom.strimma.data.health

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "heart_rate_samples",
    foreignKeys = [
        ForeignKey(
            entity = StoredExerciseSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class HeartRateSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val time: Long,
    val bpm: Int
)
