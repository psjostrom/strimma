package com.psjostrom.strimma.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.calendar.MetabolicProfile

enum class ExerciseCategory(
    val emoji: String,
    val labelRes: Int,
    val defaultMetabolicProfile: MetabolicProfile,
    val keywords: List<String>
) {
    RUNNING(
        "\uD83C\uDFC3", R.string.exercise_type_running, MetabolicProfile.AEROBIC,
        listOf("run", "jog", "sprint", "löpning")
    ),
    WALKING(
        "\uD83D\uDEB6", R.string.exercise_type_walking, MetabolicProfile.AEROBIC,
        listOf("walk", "promenad")
    ),
    HIKING(
        "\u26F0\uFE0F", R.string.exercise_type_hiking, MetabolicProfile.AEROBIC,
        listOf("hike", "hiking", "vandring")
    ),
    CYCLING(
        "\uD83D\uDEB4", R.string.exercise_type_cycling, MetabolicProfile.AEROBIC,
        listOf("bike", "cycle", "cykel")
    ),
    SWIMMING(
        "\uD83C\uDFCA", R.string.exercise_type_swimming, MetabolicProfile.AEROBIC,
        listOf("swim", "simning")
    ),
    STRENGTH(
        "\uD83C\uDFCB\uFE0F", R.string.exercise_type_strength, MetabolicProfile.RESISTANCE,
        listOf("gym", "strength", "weights", "lift", "styrk")
    ),
    YOGA(
        "\uD83E\uDDD8", R.string.exercise_type_yoga, MetabolicProfile.AEROBIC,
        listOf("yoga", "pilates")
    ),
    ROWING(
        "\uD83D\uDEA3", R.string.exercise_type_rowing, MetabolicProfile.AEROBIC,
        listOf("row", "erg", "rodd")
    ),
    SKIING(
        "\u26F7\uFE0F", R.string.exercise_type_skiing, MetabolicProfile.AEROBIC,
        listOf("ski", "snowboard", "skid")
    ),
    CLIMBING(
        "\uD83E\uDDD7", R.string.exercise_type_climbing, MetabolicProfile.RESISTANCE,
        listOf("climb", "boulder", "klättr")
    ),
    MARTIAL_ARTS(
        "\uD83E\uDD4A", R.string.exercise_type_martial_arts, MetabolicProfile.HIGH_INTENSITY,
        listOf("martial", "boxing", "mma", "kampsport")
    ),
    OTHER(
        "\uD83C\uDFCB\uFE0F", R.string.exercise_type_other, MetabolicProfile.AEROBIC,
        emptyList()
    );

    companion object {
        fun fromHCType(type: Int): ExerciseCategory = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> RUNNING
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> WALKING
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> HIKING
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> CYCLING
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> SWIMMING
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> STRENGTH
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> YOGA
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> ROWING
            ExerciseSessionRecord.EXERCISE_TYPE_SKIING,
            ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> SKIING
            ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> CLIMBING
            ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> MARTIAL_ARTS
            else -> OTHER
        }

        fun fromTitle(title: String): ExerciseCategory {
            val lower = title.lowercase()
            for (category in entries) {
                if (category.keywords.any { lower.contains(it) }) return category
            }
            return OTHER
        }
    }
}
