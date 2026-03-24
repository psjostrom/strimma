package com.psjostrom.strimma.data.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata as HcMetadata
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.BloodGlucose
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.receiver.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

enum class HealthConnectStatus { AVAILABLE, NOT_INSTALLED, NOT_SUPPORTED }

@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(BloodGlucoseRecord::class)
    )

    private fun getClient(): HealthConnectClient? {
        return if (isAvailable() == HealthConnectStatus.AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    }

    fun isAvailable(): HealthConnectStatus {
        val status = HealthConnectClient.getSdkStatus(context)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            return if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                HealthConnectStatus.NOT_INSTALLED
            } else {
                HealthConnectStatus.NOT_SUPPORTED
            }
        }
        // SDK reports available, but verify the provider is actually usable
        // On some devices, getSdkStatus returns AVAILABLE even when the HC app isn't installed
        return try {
            HealthConnectClient.getOrCreate(context)
            HealthConnectStatus.AVAILABLE
        } catch (_: Exception) {
            DebugLog.log("HC: SDK reports available but provider not usable")
            HealthConnectStatus.NOT_INSTALLED
        }
    }

    suspend fun hasPermissions(): Boolean {
        val client = getClient() ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        val missing = permissions - granted
        if (missing.isNotEmpty()) {
            DebugLog.log("HC: Missing permissions: $missing")
            DebugLog.log("HC: Granted: ${granted.size}, Requested: ${permissions.size}")
        }
        return missing.isEmpty()
    }

    fun createPermissionContract(): ActivityResultContract<Set<String>, Set<String>> {
        return PermissionController.createRequestPermissionResultContract()
    }

    @Suppress("LongMethod") // Single cohesive read operation with sub-record aggregation
    suspend fun getExerciseSessions(
        since: Instant
    ): List<Pair<StoredExerciseSession, List<HeartRateSample>>> {
        val client = getClient() ?: return emptyList()

        val sessions = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.after(since)
            )
        ).records

        return sessions.map { session ->
            val startMs = session.startTime.toEpochMilli()
            val endMs = session.endTime.toEpochMilli()
            val timeFilter = TimeRangeFilter.between(session.startTime, session.endTime)

            // Heart rate
            val hrRecords = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = timeFilter
                )
            ).records
            val hrSamples = hrRecords.flatMap { record ->
                record.samples.map { sample ->
                    HeartRateSample(
                        sessionId = session.metadata.id,
                        time = sample.time.toEpochMilli(),
                        bpm = sample.beatsPerMinute.toInt()
                    )
                }
            }

            // Steps
            val stepsRecords = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = timeFilter
                )
            ).records
            val totalSteps = stepsRecords.sumOf { it.count }.takeIf { it > 0 }?.toInt()

            // Active calories
            val calRecords = client.readRecords(
                ReadRecordsRequest(
                    recordType = ActiveCaloriesBurnedRecord::class,
                    timeRangeFilter = timeFilter
                )
            ).records
            val totalCalories = calRecords.sumOf { it.energy.inKilocalories }.takeIf { it > 0.0 }

            val stored = StoredExerciseSession(
                id = session.metadata.id,
                type = session.exerciseType,
                startTime = startMs,
                endTime = endMs,
                title = session.title,
                totalSteps = totalSteps,
                activeCalories = totalCalories
            )

            stored to hrSamples
        }
    }

    suspend fun writeGlucoseReading(reading: GlucoseReading) {
        val client = getClient() ?: return
        try {
            val instant = Instant.ofEpochMilli(reading.ts)
            val record = BloodGlucoseRecord(
                time = instant,
                zoneOffset = ZoneOffset.systemDefault().rules.getOffset(instant),
                metadata = HcMetadata.autoRecorded(
                    device = Device(type = Device.TYPE_PHONE)
                ),
                level = BloodGlucose.milligramsPerDeciliter(reading.sgv.toDouble()),
                specimenSource = BloodGlucoseRecord.SPECIMEN_SOURCE_INTERSTITIAL_FLUID,
                mealType = MealType.MEAL_TYPE_UNKNOWN,
                relationToMeal = BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL
            )
            client.insertRecords(listOf(record))
        } catch (
            @Suppress("TooGenericExceptionCaught") // HC SDK can throw various platform exceptions
            e: Exception
        ) {
            DebugLog.log("HC write failed: ${e.message}")
        }
    }

    suspend fun getChangesToken(): String? {
        val client = getClient() ?: return null
        return try {
            client.getChangesToken(
                ChangesTokenRequest(recordTypes = setOf(ExerciseSessionRecord::class))
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") // HC SDK can throw various platform exceptions
            e: Exception
        ) {
            DebugLog.log("HC getChangesToken failed: ${e.message}")
            null
        }
    }

    data class ChangesResult(
        val hasChanges: Boolean,
        val nextToken: String?,
        val tokenExpired: Boolean = false
    )

    suspend fun getChanges(token: String): ChangesResult {
        val client = getClient() ?: return ChangesResult(
            hasChanges = false, nextToken = null, tokenExpired = true
        )
        return try {
            var nextToken = token
            var hasChanges = false
            var hasMore = true
            while (hasMore) {
                val response = client.getChanges(nextToken)
                hasMore = response.hasMore
                nextToken = response.nextChangesToken
                if (response.changes.any { it is UpsertionChange || it is DeletionChange }) {
                    hasChanges = true
                }
            }
            ChangesResult(hasChanges = hasChanges, nextToken = nextToken)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            DebugLog.log("HC getChanges failed (token expired?): ${e.message}")
            ChangesResult(hasChanges = false, nextToken = null, tokenExpired = true)
        }
    }
}
