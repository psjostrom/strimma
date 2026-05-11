package com.psjostrom.strimma.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "treatments",
    // Local retention is unbounded — every consumer (since/allSince/insulinSince/
    // carbsInRange) filters by createdAt, so this index is what keeps reads cheap
    // as the table grows.
    indices = [Index("createdAt")]
)
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
