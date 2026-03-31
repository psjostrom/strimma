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
        listOf(
            "run", "running", "jog", "jogging", "sprint", "sprinting",
            "5k", "10k", "half marathon", "marathon", "parkrun", "trail run",
            "treadmill", "track run", "track workout",
            "löpning", "löp", "jogga", "lopp", "lauftraining", "laufen",
            "course", "courir", "correr", "corrida", "carrera"
        )
    ),
    WALKING(
        "\uD83D\uDEB6", R.string.exercise_type_walking, MetabolicProfile.AEROBIC,
        listOf(
            "walk", "walking", "stroll", "hike-walk",
            "promenad", "promenera", "spazier", "spaziergang",
            "marche", "caminar", "caminata"
        )
    ),
    HIKING(
        "\u26F0\uFE0F", R.string.exercise_type_hiking, MetabolicProfile.AEROBIC,
        listOf(
            "hike", "hiking", "trek", "trekking", "backpack",
            "vandring", "vandra", "bergwandern", "wandern",
            "randonnée", "senderismo", "excursión"
        )
    ),
    CYCLING(
        "\uD83D\uDEB4", R.string.exercise_type_cycling, MetabolicProfile.AEROBIC,
        listOf(
            "bike", "biking", "cycle", "cycling", "bicycle", "spinning", "spin class",
            "zwift", "peloton", "velodrome", "criterium", "crit ride",
            "cykel", "cykl", "radfahren", "radtour",
            "vélo", "cyclisme", "ciclismo", "bicicleta"
        )
    ),
    SWIMMING(
        "\uD83C\uDFCA", R.string.exercise_type_swimming, MetabolicProfile.AEROBIC,
        listOf(
            "swim", "swimming", "pool swim", "open water", "triathlon swim",
            "simning", "simma", "simträning", "schwimmen",
            "natation", "nager", "nadar", "natación"
        )
    ),
    STRENGTH(
        "\uD83C\uDFCB\uFE0F", R.string.exercise_type_strength, MetabolicProfile.RESISTANCE,
        listOf(
            "gym", "strength", "weights", "weightlifting", "powerlifting", "deadlift",
            "squat", "bench press", "barbell", "dumbbell", "kettlebell",
            "crossfit", "calisthenics", "bodyweight", "resistance",
            "lift", "lifting",
            "styrk", "styrketräning", "vikter", "krafttraining",
            "musculation", "musculación", "pesas"
        )
    ),
    YOGA(
        "\uD83E\uDDD8", R.string.exercise_type_yoga, MetabolicProfile.AEROBIC,
        listOf(
            "yoga", "pilates", "stretch", "stretching", "flexibility", "mobility",
            "vinyasa", "ashtanga", "bikram", "yin yoga", "hatha",
            "tai chi", "qigong",
            "rörlighet", "dehnen", "étirement", "estiramiento"
        )
    ),
    ROWING(
        "\uD83D\uDEA3", R.string.exercise_type_rowing, MetabolicProfile.AEROBIC,
        listOf(
            "rowing", "row machine", "ergometer", "concept2", "c2",
            "kayak", "canoe", "paddling", "paddle",
            "rodd", "roddmaskin", "paddla", "rudern", "aviron", "remo"
        )
    ),
    SKIING(
        "\u26F7\uFE0F", R.string.exercise_type_skiing, MetabolicProfile.AEROBIC,
        listOf(
            "ski", "skiing", "snowboard", "snowboarding",
            "cross-country", "xc ski", "langlauf", "downhill", "slalom",
            "skid", "skidor", "längdskid", "utför",
            "esquí", "esquiar"
        )
    ),
    CLIMBING(
        "\uD83E\uDDD7", R.string.exercise_type_climbing, MetabolicProfile.RESISTANCE,
        listOf(
            "climb", "climbing", "boulder", "bouldering", "top rope", "lead climb",
            "rock climbing", "wall climbing",
            "klättr", "klättring", "klettern", "escalade", "escalada"
        )
    ),
    MARTIAL_ARTS(
        "\uD83E\uDD4A", R.string.exercise_type_martial_arts, MetabolicProfile.HIGH_INTENSITY,
        listOf(
            "martial", "boxing", "kickboxing", "muay thai", "mma",
            "karate", "taekwondo", "judo", "jiu-jitsu", "jiu jitsu", "jiujitsu",
            "bjj", "wrestling", "krav maga", "capoeira", "kung fu",
            "fencing", "sparring", "self-defense", "self defense",
            "kampsport", "brottning", "boxning", "kampfsport", "boxen",
            "arts martiaux", "boxe", "lutte", "artes marciales", "lucha"
        )
    ),
    OTHER(
        "\uD83C\uDFCB\uFE0F", R.string.exercise_type_other, MetabolicProfile.AEROBIC,
        emptyList()
    );

    val displayName: String
        get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        private val keywordIndex: List<Pair<ExerciseCategory, List<String>>> by lazy {
            entries.map { cat -> cat to cat.keywords.map { it.lowercase() } }
        }

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
            for ((category, kws) in keywordIndex) {
                if (kws.any { lower.contains(it) }) return category
            }
            return OTHER
        }
    }
}
