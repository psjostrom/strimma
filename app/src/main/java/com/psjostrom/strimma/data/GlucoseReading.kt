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
) {
    companion object {
        const val MIN_VALID_SGV = 18
        const val MAX_VALID_SGV = 900

        fun isValidSgv(value: Int): Boolean = value in MIN_VALID_SGV..MAX_VALID_SGV
        fun isValidSgv(value: Double): Boolean = value >= MIN_VALID_SGV && value <= MAX_VALID_SGV
    }
}
