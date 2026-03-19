package com.psjostrom.strimma.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "treatments")
data class Treatment(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val eventType: String,
    val insulin: Double?,
    val carbs: Double?,
    val basalRate: Double?,
    val duration: Int?,
    val enteredBy: String?,
    val fetchedAt: Long
)
