package com.psjostrom.strimma.data.health

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exercise_sessions")
data class StoredExerciseSession(
    @PrimaryKey val id: String,
    val type: Int,
    val startTime: Long,
    val endTime: Long,
    val title: String?,
    val totalSteps: Int?,
    val activeCalories: Double?
)
