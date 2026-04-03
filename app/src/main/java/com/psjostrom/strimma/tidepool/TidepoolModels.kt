package com.psjostrom.strimma.tidepool

import com.psjostrom.strimma.data.GlucoseReading
import kotlinx.serialization.Serializable
import java.util.TimeZone

/**
 * Tidepool CBG (Continuous Blood Glucose) record.
 * Represents a single CGM reading in Tidepool's format.
 */
@Serializable
data class CbgRecord(
    val type: String = "cbg",
    val units: String = "mg/dL",
    val value: Int,
    val time: String,
    val deviceTime: String,
    val timezoneOffset: Int,
    val origin: Origin
) {
    @Serializable
    data class Origin(val id: String)

    companion object {
        private const val MIN_GLUCOSE = 39
        private const val MAX_GLUCOSE = 500
        private const val EPOCH_2020 = 1577836800000L // 2020-01-01 00:00:00 UTC

        /**
         * Creates a CbgRecord from a GlucoseReading.
         */
        fun fromReading(reading: GlucoseReading): CbgRecord {
            return CbgRecord(
                value = reading.sgv,
                time = TidepoolDateUtil.toUtcIso8601(reading.ts),
                deviceTime = TidepoolDateUtil.toLocalNoZone(reading.ts),
                timezoneOffset = TidepoolDateUtil.getTimezoneOffsetMinutes(reading.ts),
                origin = Origin(id = "strimma-cbg-${reading.ts}")
            )
        }

        /**
         * Validates if a reading is suitable for Tidepool upload.
         * Rejects readings with:
         * - Glucose values outside physiological range (39-500 mg/dL)
         * - Timestamps before 2020-01-01 (likely data corruption)
         * - Future timestamps (clock skew or invalid data)
         */
        fun isValidForUpload(reading: GlucoseReading): Boolean {
            val now = System.currentTimeMillis()
            return reading.sgv in MIN_GLUCOSE..MAX_GLUCOSE &&
                    reading.ts >= EPOCH_2020 &&
                    reading.ts <= now
        }
    }
}

/**
 * Tidepool dataset creation request.
 * Initiates a new upload session.
 */
@Serializable
data class DatasetRequest(
    val type: String = "upload",
    val dataSetType: String = "continuous",
    val client: ClientInfo,
    val deduplicator: Deduplicator = Deduplicator(),
    val deviceManufacturers: List<String> = listOf("Strimma"),
    val deviceModel: String = "Strimma CGM Companion",
    val deviceTags: List<String> = listOf("cgm"),
    val time: String,
    val computerTime: String,
    val timezoneOffset: Int,
    val timezone: String,
    val timeProcessing: String = "none",
    val version: String
) {
    @Serializable
    data class ClientInfo(
        val name: String,
        val version: String
    )

    @Serializable
    data class Deduplicator(
        val name: String = "org.tidepool.deduplicator.dataset.delete.origin"
    )
}

/**
 * Tidepool dataset creation response.
 * The API wraps the dataset in a "data" envelope: {"data": {"uploadId": "..."}}.
 */
@Serializable
data class DatasetResponse(
    val uploadId: String? = null,
    val id: String? = null
)
