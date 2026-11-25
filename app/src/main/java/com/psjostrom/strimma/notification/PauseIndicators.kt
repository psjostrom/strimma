package com.psjostrom.strimma.notification

import androidx.annotation.StringRes
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.MS_PER_MINUTE

/*
 * The user must be able to tell exactly which alerts are paused, and for how long
 * (IEC 60601-1-8, 6.8). The foreground notification, the header icon, the pause pills
 * and the pause sheet all show pause state, so this file decides it once for all of
 * them. It works from the stored pauses and the current wall-clock time, because a
 * pause stays stored after it expires. It names the paused alert level, because a
 * snooze of a Low Soon alert pauses only Low Soon, and Low and Urgent Low still fire.
 */

enum class PauseScope { ALL, LOW, HIGH }

data class PauseIndicator(val scope: PauseScope, val level: Int, val expiryMs: Long)

/**
 * The pauses to show at [nowMs], Low before High, so a cut-off notification line still
 * shows a Low pause. Low and High that share an expiry and
 * both silence every level become one ALL indicator, which is the state "Pause all"
 * produces. Any other combination shows one indicator per category with its own expiry.
 */
fun activePauseIndicators(low: AlertPause?, high: AlertPause?, nowMs: Long): List<PauseIndicator> {
    val activeLow = low?.takeIf { it.expiryMs > nowMs }
    val activeHigh = high?.takeIf { it.expiryMs > nowMs }
    if (activeLow != null && activeLow == activeHigh && activeLow.level == AlertManager.ALERT_LEVEL_URGENT) {
        return listOf(PauseIndicator(PauseScope.ALL, activeLow.level, activeLow.expiryMs))
    }
    return listOfNotNull(
        activeLow?.let { PauseIndicator(PauseScope.LOW, it.level, it.expiryMs) },
        activeHigh?.let { PauseIndicator(PauseScope.HIGH, it.level, it.expiryMs) },
    )
}

/** Label of the main-screen pill and the pause sheet. Takes the countdown as `%1$s`. */
@StringRes
fun PauseIndicator.pillLabel(): Int = when (scope) {
    PauseScope.ALL -> R.string.pause_all_active
    PauseScope.LOW -> when (level) {
        AlertManager.ALERT_LEVEL_URGENT -> R.string.pause_low_active
        AlertManager.ALERT_LEVEL_REGULAR -> R.string.pause_low_regular_active
        else -> R.string.pause_low_soon_active
    }
    PauseScope.HIGH -> when (level) {
        AlertManager.ALERT_LEVEL_URGENT -> R.string.pause_high_active
        AlertManager.ALERT_LEVEL_REGULAR -> R.string.pause_high_regular_active
        else -> R.string.pause_high_soon_active
    }
}

/** Label of the segment in the foreground notification. Takes the countdown as `%1$s`. */
@StringRes
fun PauseIndicator.notificationLabel(): Int = when (scope) {
    PauseScope.ALL -> R.string.notif_alerts_paused_all
    PauseScope.LOW -> when (level) {
        AlertManager.ALERT_LEVEL_URGENT -> R.string.notif_alerts_paused_low
        AlertManager.ALERT_LEVEL_REGULAR -> R.string.notif_alerts_paused_low_regular
        else -> R.string.notif_alerts_paused_low_soon
    }
    PauseScope.HIGH -> when (level) {
        AlertManager.ALERT_LEVEL_URGENT -> R.string.notif_alerts_paused_high
        AlertManager.ALERT_LEVEL_REGULAR -> R.string.notif_alerts_paused_high_regular
        else -> R.string.notif_alerts_paused_high_soon
    }
}

private const val MINUTES_PER_HOUR = 60

/** Remaining pause time as "1h 5m" or "5m", rounded down to the minute. */
fun formatPauseCountdown(expiryMs: Long, nowMs: Long): String {
    val totalMin = ((expiryMs - nowMs) / MS_PER_MINUTE).toInt().coerceAtLeast(0)
    val hours = totalMin / MINUTES_PER_HOUR
    val minutes = totalMin % MINUTES_PER_HOUR
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
