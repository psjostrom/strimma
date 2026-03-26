package com.psjostrom.strimma.data.calendar

data class WorkoutEvent(
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val category: WorkoutCategory,
    val calendarId: Long
)
