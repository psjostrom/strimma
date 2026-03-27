package com.psjostrom.strimma.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.notification.AlertCategory
import com.psjostrom.strimma.ui.theme.AboveHigh
import com.psjostrom.strimma.ui.theme.BelowLow
import kotlinx.coroutines.delay

private val DURATIONS = listOf(
    3_600_000L to R.string.pause_duration_1h,
    5_400_000L to R.string.pause_duration_1_5h,
    7_200_000L to R.string.pause_duration_2h
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PauseAlertsSheet(
    pauseLowExpiryMs: Long?,
    pauseHighExpiryMs: Long?,
    onPause: (AlertCategory, Long) -> Unit,
    onCancel: (AlertCategory) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        PauseAlertsSheetContent(
            pauseLowExpiryMs = pauseLowExpiryMs,
            pauseHighExpiryMs = pauseHighExpiryMs,
            onPause = { cat, dur ->
                onPause(cat, dur)
                onDismiss()
            },
            onCancel = onCancel
        )
    }
}

@Composable
fun PauseAlertsSheetContent(
    pauseLowExpiryMs: Long?,
    pauseHighExpiryMs: Long?,
    onPause: (AlertCategory, Long) -> Unit,
    onCancel: (AlertCategory) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        Text(
            text = stringResource(R.string.pause_alerts),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        PauseCategoryRow(
            label = stringResource(R.string.pause_low_alerts),
            color = BelowLow,
            expiryMs = pauseLowExpiryMs,
            category = AlertCategory.LOW,
            onPause = onPause,
            onCancel = onCancel
        )

        Spacer(modifier = Modifier.height(16.dp))

        PauseCategoryRow(
            label = stringResource(R.string.pause_high_alerts),
            color = AboveHigh,
            expiryMs = pauseHighExpiryMs,
            category = AlertCategory.HIGH,
            onPause = onPause,
            onCancel = onCancel
        )
    }
}

@Composable
private fun PauseCategoryRow(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    expiryMs: Long?,
    category: AlertCategory,
    onPause: (AlertCategory, Long) -> Unit,
    onCancel: (AlertCategory) -> Unit
) {
    Column {
        Text(
            text = label,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (expiryMs != null && expiryMs > System.currentTimeMillis()) {
            // Active pause — show countdown + cancel
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                var remainingText by remember { mutableStateOf("") }
                LaunchedEffect(expiryMs) {
                    while (true) {
                        val remaining = expiryMs - System.currentTimeMillis()
                        if (remaining <= 0) break
                        val totalMin = (remaining / 60_000).toInt()
                        val hours = totalMin / 60
                        val min = totalMin % 60
                        remainingText = if (hours > 0) "${hours}h ${min}m left" else "${min}m left"
                        delay(10_000)
                    }
                }
                Text(
                    text = "Paused · $remainingText",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onCancel(category) }) {
                    Text(stringResource(R.string.pause_cancel))
                }
            }
        } else {
            // Not paused — show duration chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DURATIONS.forEach { (durationMs, labelRes) ->
                    FilledTonalButton(
                        onClick = { onPause(category, durationMs) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(stringResource(labelRes), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
