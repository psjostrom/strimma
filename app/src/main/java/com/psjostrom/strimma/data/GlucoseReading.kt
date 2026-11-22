package com.psjostrom.strimma.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "readings",
    // Local retention is unbounded — unpushed() scans by `pushed=0` every retry
    // tick, so this index is what keeps the scan cheap as the table grows.
    indices = [Index("pushed")]
)
data class GlucoseReading(
    @PrimaryKey val ts: Long,
    val sgv: Int,
    val direction: String,
    val delta: Double?,
    val pushed: Int = 0,
    // 1 once Tidepool has the reading. A flag per reading, not a time cursor, because a
    // follower catch-up or a Nightscout pull stores readings older than ones already sent.
    @ColumnInfo(defaultValue = "0") val tidepoolUploaded: Int = 0
) {
    companion object {
        const val MIN_VALID_SGV = 18
        const val MAX_VALID_SGV = 900

        fun isValidSgv(value: Int): Boolean = value in MIN_VALID_SGV..MAX_VALID_SGV
        fun isValidSgv(value: Double): Boolean = value >= MIN_VALID_SGV && value <= MAX_VALID_SGV

        // A reading stamped in the future becomes the latest reading until the clock
        // catches up. The main screen shows it as current, the stale alert stays quiet,
        // and the Nightscout follower asks only for entries newer than it, so real
        // readings stop arriving. Up to five minutes ahead is allowed for clock drift
        // between the phone and the source.
        const val MAX_CLOCK_SKEW_MS = 5 * 60_000L

        fun isFutureTs(ts: Long, now: Long): Boolean = ts > now + MAX_CLOCK_SKEW_MS
    }
}
