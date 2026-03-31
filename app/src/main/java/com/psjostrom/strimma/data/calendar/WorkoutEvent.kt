package com.psjostrom.strimma.data.calendar

import com.psjostrom.strimma.data.health.ExerciseCategory

data class WorkoutEvent(
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val category: ExerciseCategory,
    val metabolicProfile: MetabolicProfile,
    val calendarId: Long
)
