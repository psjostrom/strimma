package com.psjostrom.strimma.data.notification

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

/**
 * Configuration for the foreground notification's action button.
 *
 * `snoozeCategory` and `snoozeDuration` are persisted independently of `type`, so a user
 * who picks SNOOZE/HIGH/M30 and then switches the action to NONE or WORKOUT_TOGGLE keeps
 * their snooze preferences — toggling back to SNOOZE restores the previous choice.
 */
data class NotificationActionConfig(
    val type: NotificationActionType,
    val snoozeCategory: SnoozeCategory,
    val snoozeDuration: SnoozeDuration,
) {
    companion object {
        /** Single source of truth for the action-button defaults. Every persistence,
         *  collectAsState, stateIn, and receiver-fallback site reads from here so a
         *  default change updates one place, not nine. */
        val DEFAULT = NotificationActionConfig(
            type = NotificationActionType.WORKOUT_TOGGLE,
            snoozeCategory = SnoozeCategory.ALL,
            snoozeDuration = SnoozeDuration.H1,
        )
    }
}
