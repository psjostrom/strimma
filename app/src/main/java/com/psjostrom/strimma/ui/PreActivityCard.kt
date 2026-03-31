package com.psjostrom.strimma.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.calendar.GuidanceState
import com.psjostrom.strimma.data.calendar.ReadinessLevel
import com.psjostrom.strimma.ui.theme.AboveHigh
import com.psjostrom.strimma.ui.theme.BelowLow
import com.psjostrom.strimma.ui.theme.InRange
import com.psjostrom.strimma.ui.theme.LightTintDanger
import com.psjostrom.strimma.ui.theme.LightTintInRange
import com.psjostrom.strimma.ui.theme.LightTintWarning
import com.psjostrom.strimma.ui.theme.TintDanger
import com.psjostrom.strimma.ui.theme.TintInRange
import com.psjostrom.strimma.ui.theme.TintWarning

private const val MINUTES_PER_HOUR = 60

@Composable
fun PreActivityCard(
    state: GuidanceState.WorkoutApproaching,
    glucoseUnit: GlucoseUnit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (bgColor, badgeColor, badgeText) = when (state.readiness) {
        ReadinessLevel.READY -> Triple(if (isDark) TintInRange else LightTintInRange, InRange, "READY")
        ReadinessLevel.CAUTION -> Triple(if (isDark) TintWarning else LightTintWarning, AboveHigh, "HEADS UP")
        ReadinessLevel.WAIT -> Triple(if (isDark) TintDanger else LightTintDanger, BelowLow, "HOLD ON")
    }

    val timeText = formatTimeUntil(state.event.startTime - System.currentTimeMillis())

    // Pick the single most actionable line to show
    val actionText = state.suggestions.firstOrNull()
        ?: state.reasons.firstOrNull()?.message

    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = badgeText,
                    color = badgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "${state.event.title} in $timeText",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (actionText != null) {
                Text(
                    text = actionText,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

internal fun formatTimeUntil(ms: Long): String {
    if (ms <= 0) return "now"
    val totalMinutes = (ms / MS_PER_MINUTE).toInt()
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}min"
        hours > 0 -> "${hours}h"
        else -> "${minutes}min"
    }
}
