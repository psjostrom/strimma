package com.psjostrom.strimma.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "readings")
data class GlucoseReading(
    @PrimaryKey val ts: Long,
    val sgv: Int,
    val mmol: Double,
    val direction: String,
    val deltaMmol: Double?,
    val pushed: Int = 0
)
