package com.psjostrom.strimma.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.data.GlucoseUnit
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

private const val MS_PER_MINUTE = 60_000
private const val MINUTES_PER_HOUR = 60

@Composable
fun PreActivityCard(
    state: GuidanceState.WorkoutApproaching,
    glucoseUnit: GlucoseUnit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (bgColor, badgeColor, badgeText) = when (state.readiness) {
        ReadinessLevel.READY -> Triple(if (isDark) TintInRange else LightTintInRange, InRange, "READY")
        ReadinessLevel.CAUTION -> Triple(if (isDark) TintWarning else LightTintWarning, AboveHigh, "HEADS UP")
        ReadinessLevel.WAIT -> Triple(if (isDark) TintDanger else LightTintDanger, BelowLow, "HOLD ON")
    }

    val timeText = formatTimeUntil(state.event.startTime - System.currentTimeMillis())
    val targetLow = glucoseUnit.format(state.targetLowMgdl.toDouble())
    val targetHigh = glucoseUnit.format(state.targetHighMgdl.toDouble())

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "${state.event.title} in $timeText",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = badgeText,
                color = badgeColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Target: $targetLow\u2013$targetHigh ${glucoseUnit.label}",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp
            )
            val currentLine = buildString {
                append("Current: ${glucoseUnit.format(state.currentBgMgdl)} ${state.trendArrow}")
                if (state.iob > 0.0) append("  \u00B7  IOB ${"%.1f".format(state.iob)}u")
            }
            Text(currentLine, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp)

            if (state.reasons.isNotEmpty()) {
                for (reason in state.reasons) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(reason.message, color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
                }
            }

            if (state.suggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                for (suggestion in state.suggestions) {
                    Text(
                        text = suggestion,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
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
