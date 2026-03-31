package com.psjostrom.strimma.data.meal

enum class CarbSizeBucket(val label: String) {
    SMALL("< 20g"),
    MEDIUM("20–50g"),
    LARGE("> 50g");

    companion object {
        private const val SMALL_THRESHOLD = 20.0
        private const val LARGE_THRESHOLD = 50.0

        fun fromGrams(grams: Double): CarbSizeBucket = when {
            grams < SMALL_THRESHOLD -> SMALL
            grams > LARGE_THRESHOLD -> LARGE
            else -> MEDIUM
        }
    }
}
