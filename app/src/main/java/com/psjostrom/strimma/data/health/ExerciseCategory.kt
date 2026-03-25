package com.psjostrom.strimma.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.psjostrom.strimma.R

enum class ExerciseCategory(val emoji: String, val labelRes: Int) {
    RUNNING("\uD83C\uDFC3", R.string.exercise_type_running),
    WALKING("\uD83D\uDEB6", R.string.exercise_type_walking),
    CYCLING("\uD83D\uDEB4", R.string.exercise_type_cycling),
    SWIMMING("\uD83C\uDFCA", R.string.exercise_type_swimming),
    OTHER("\uD83C\uDFCB\uFE0F", R.string.exercise_type_other);

    companion object {
        fun fromHCType(type: Int): ExerciseCategory = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> RUNNING
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> WALKING
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> CYCLING
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> SWIMMING
            else -> OTHER
        }
    }
}
