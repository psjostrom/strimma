package com.psjostrom.strimma.notification

enum class NotificationActionType {
    NONE,
    WORKOUT_TOGGLE,
    SNOOZE,
}

enum class SnoozeCategory {
    ALL,
    HIGH,
    LOW,
}

enum class SnoozeDuration(val durationMs: Long) {
    M15(15L * 60_000L),
    M30(30L * 60_000L),
    H1(60L * 60_000L),
    H2(2L * 60L * 60_000L),
    H3(3L * 60L * 60_000L),
}

data class NotificationActionConfig(
    val type: NotificationActionType,
    val snoozeCategory: SnoozeCategory,
    val snoozeDuration: SnoozeDuration,
)
