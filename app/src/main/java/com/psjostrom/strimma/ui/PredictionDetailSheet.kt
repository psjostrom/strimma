package com.psjostrom.strimma.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.graph.CrossingType
import com.psjostrom.strimma.graph.Prediction
import com.psjostrom.strimma.graph.PredictionComputer
import com.psjostrom.strimma.graph.ThresholdCrossing
import com.psjostrom.strimma.notification.AlertCategory
import com.psjostrom.strimma.ui.theme.AboveHigh
import com.psjostrom.strimma.ui.theme.BelowLow
import com.psjostrom.strimma.ui.theme.InRange
import com.psjostrom.strimma.ui.theme.LightTintDanger
import com.psjostrom.strimma.ui.theme.LightTintWarning
import com.psjostrom.strimma.ui.theme.TintDanger
import com.psjostrom.strimma.ui.theme.TintWarning

internal const val QUICK_PAUSE_MS = 1_800_000L // 30 min

@Composable
internal fun PredictionDetailSheet(
    prediction: Prediction,
    crossing: ThresholdCrossing,
    readings: List<GlucoseReading>,
    glucoseUnit: GlucoseUnit,
    bgLow: Float,
    bgHigh: Float,
    onPauseAlerts: (AlertCategory, Long) -> Unit,
    onDismiss: () -> Unit,
    isPaused: Boolean = false
) {
    val crossingColor = when (crossing.type) {
        CrossingType.LOW -> BelowLow
        CrossingType.HIGH -> AboveHigh
    }
    val crossingText = when (crossing.type) {
        CrossingType.LOW -> stringResource(R.string.main_prediction_low, crossing.minutesUntil)
        CrossingType.HIGH -> stringResource(R.string.main_prediction_high, crossing.minutesUntil)
    }
    val velocity = remember(readings) { PredictionComputer.currentVelocity(readings) }
    val projectedAt5 = prediction.points.getOrNull(4) // index 4 = minute 5
    val projectedAt10 = prediction.points.getOrNull(9)
    val projectedAt15 = prediction.points.getOrNull(14)

    val thresholdLabel = when (crossing.type) {
        CrossingType.LOW -> stringResource(R.string.main_prediction_threshold, glucoseUnit.formatThreshold(bgLow))
        CrossingType.HIGH -> stringResource(R.string.main_prediction_threshold, glucoseUnit.formatThreshold(bgHigh))
    }

    StrimmaBottomSheet(onDismiss = onDismiss) {
        // Hero crossing warning
        Text(
            text = stringResource(R.string.main_prediction_detail_title),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = crossingText,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = crossingColor
        )

        Spacer(Modifier.height(16.dp))

        // Rate of change
        if (velocity != null) {
            PredictionDetailRow(
                label = stringResource(R.string.main_prediction_rate),
                value = stringResource(
                    R.string.main_prediction_rate_value,
                    glucoseUnit.formatDelta(velocity)
                )
            )
        }

        // Threshold
        PredictionDetailRow(
            label = thresholdLabel,
            value = stringResource(R.string.main_prediction_in_min, crossing.minutesUntil)
        )

        Spacer(Modifier.height(12.dp))

        // Projected values
        Text(
            text = stringResource(R.string.main_prediction_projected),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))

        val projections = buildList {
            projectedAt5?.let { add(5 to it.mgdl) }
            projectedAt10?.let { add(10 to it.mgdl) }
            projectedAt15?.let { add(15 to it.mgdl) }
        }
        projections.forEach { (min, mgdl) ->
            val projColor = when {
                mgdl < bgLow -> BelowLow
                mgdl > bgHigh -> AboveHigh
                else -> InRange
            }
            PredictionDetailRow(
                label = stringResource(R.string.main_prediction_in_min, min),
                value = glucoseUnit.format(mgdl),
                valueColor = projColor
            )
        }

        Spacer(Modifier.height(20.dp))

        // Pause alerts button — single action, 30 min
        if (!isPaused) {
            val alertCategory = when (crossing.type) {
                CrossingType.LOW -> AlertCategory.LOW
                CrossingType.HIGH -> AlertCategory.HIGH
            }
            val pauseTypeLabel = when (crossing.type) {
                CrossingType.LOW -> stringResource(R.string.main_prediction_pause_low)
                CrossingType.HIGH -> stringResource(R.string.main_prediction_pause_high)
            }
            val pauseLabel = stringResource(R.string.main_prediction_pause_alerts, pauseTypeLabel) +
                " · " + stringResource(R.string.pause_duration_30m)
            val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            val buttonBg = when (crossing.type) {
                CrossingType.LOW -> if (isDark) TintDanger else LightTintDanger
                CrossingType.HIGH -> if (isDark) TintWarning else LightTintWarning
            }
            Surface(
                onClick = {
                    onPauseAlerts(alertCategory, QUICK_PAUSE_MS)
                    onDismiss()
                },
                shape = RoundedCornerShape(12.dp),
                color = buttonBg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = pauseLabel,
                    color = crossingColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PredictionDetailRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}
