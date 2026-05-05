package com.psjostrom.strimma.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.workout.WorkoutMode
import com.psjostrom.strimma.ui.theme.TintInRange
import kotlinx.coroutines.delay

private const val PILL_REFRESH_INTERVAL_MS = 60_000L
private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60

/**
 * Status pill for workout mode. Tap to toggle.
 *
 * - Off: outlined chip, low emphasis.
 * - On: filled pill (TintInRange) with elapsed time (manual) or generic label (calendar).
 *
 * Single-tap toggles. No confirmation sheet — Off is the safer direction, and turning On
 * is a deliberate user action that the pill clearly indicates.
 */
@Composable
fun WorkoutModePill(
    mode: WorkoutMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(100)
    val pillModifier = modifier
        .let {
            if (mode is WorkoutMode.On) {
                it.background(TintInRange, shape)
            } else {
                it.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            }
        }
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = pillModifier
    ) {
        Text(text = "🏃")
        when (mode) {
            is WorkoutMode.Off -> Text(
                text = stringResource(R.string.workout_mode),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is WorkoutMode.On -> {
                val elapsed = elapsedSince(mode.sinceMs)
                Text(
                    text = stringResource(R.string.workout_mode_active_for, elapsed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun elapsedSince(sinceMs: Long): String {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sinceMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(PILL_REFRESH_INTERVAL_MS)
        }
    }
    val totalMin = ((nowMs - sinceMs) / MS_PER_MINUTE).coerceAtLeast(0L).toInt()
    val h = totalMin / MINUTES_PER_HOUR
    val m = totalMin % MINUTES_PER_HOUR
    return "%d:%02d".format(h, m)
}
