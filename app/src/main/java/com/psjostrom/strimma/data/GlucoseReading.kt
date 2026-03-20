package com.psjostrom.strimma.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "readings")
data class GlucoseReading(
    @PrimaryKey val ts: Long,
    val sgv: Int,
    val direction: String,
    val delta: Double?,
    val pushed: Int = 0
)
