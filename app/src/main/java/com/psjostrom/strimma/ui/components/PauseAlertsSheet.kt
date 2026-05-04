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
    1_800_000L to R.string.pause_duration_30m,
    3_600_000L to R.string.pause_duration_1h,
    5_400_000L to R.string.pause_duration_1_5h,
    7_200_000L to R.string.pause_duration_2h,
    10_800_000L to R.string.pause_duration_3h
)

/**
 * Wall-clock time that recomposes its readers every [intervalMs]. Used by the pause
 * countdown text and by surfaces that need to react when an expiry passes (e.g. hide
 * a "Cancel" button after the pause expires while the sheet stays open).
 */
@Composable
fun rememberTickingNowMs(intervalMs: Long = 10_000): Long {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            delay(intervalMs)
            nowMs = System.currentTimeMillis()
        }
    }
    return nowMs
}

@Composable
fun rememberCountdownText(expiryMs: Long): String {
    var text by remember(expiryMs) {
        val remaining = expiryMs - System.currentTimeMillis()
        val totalMin = (remaining / 60_000).toInt().coerceAtLeast(0)
        val hours = totalMin / 60
        val min = totalMin % 60
        mutableStateOf(if (hours > 0) "${hours}h ${min}m" else "${min}m")
    }
    LaunchedEffect(expiryMs) {
        while (true) {
            delay(10_000)
            val remaining = expiryMs - System.currentTimeMillis()
            if (remaining <= 0) break
            val totalMin = (remaining / 60_000).toInt()
            val hours = totalMin / 60
            val min = totalMin % 60
            text = if (hours > 0) "${hours}h ${min}m" else "${min}m"
        }
    }
    return text
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PauseAlertsSheet(
    pauseLowExpiryMs: Long?,
    pauseHighExpiryMs: Long?,
    unifiedExpiryMs: Long?,
    onPause: (AlertCategory, Long) -> Unit,
    onPauseAll: (Long) -> Unit,
    onCancel: (AlertCategory) -> Unit,
    onCancelAll: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        PauseAlertsSheetContent(
            pauseLowExpiryMs = pauseLowExpiryMs,
            pauseHighExpiryMs = pauseHighExpiryMs,
            unifiedExpiryMs = unifiedExpiryMs,
            onPause = { cat, dur ->
                onPause(cat, dur)
                onDismiss()
            },
            onPauseAll = { dur ->
                onPauseAll(dur)
                onDismiss()
            },
            onCancel = onCancel,
            onCancelAll = onCancelAll
        )
    }
}

@Composable
fun PauseAlertsSheetContent(
    pauseLowExpiryMs: Long?,
    pauseHighExpiryMs: Long?,
    unifiedExpiryMs: Long?,
    onPause: (AlertCategory, Long) -> Unit,
    onPauseAll: (Long) -> Unit,
    onCancel: (AlertCategory) -> Unit,
    onCancelAll: () -> Unit
) {
    // Tick the wall clock so a pause that expires while the sheet is open clears the
    // "Cancel" affordance instead of leaving a stale countdown next to a useless button.
    val nowMs = rememberTickingNowMs()
    val unifiedActive = unifiedExpiryMs != null && unifiedExpiryMs > nowMs

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

        PauseAllRow(
            unifiedExpiryMs = unifiedExpiryMs?.takeIf { unifiedActive },
            onPauseAll = onPauseAll,
            onCancelAll = onCancelAll
        )

        // While the unified pause is active the per-category rows would just duplicate
        // a control the user already silenced together; hide them so the only Cancel is
        // the one matching the original "Pause all" intent.
        if (!unifiedActive) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            PauseCategoryRow(
                label = stringResource(R.string.pause_high_alerts),
                color = AboveHigh,
                expiryMs = pauseHighExpiryMs,
                nowMs = nowMs,
                category = AlertCategory.HIGH,
                onPause = onPause,
                onCancel = onCancel
            )

            Spacer(modifier = Modifier.height(16.dp))

            PauseCategoryRow(
                label = stringResource(R.string.pause_low_alerts),
                color = BelowLow,
                expiryMs = pauseLowExpiryMs,
                nowMs = nowMs,
                category = AlertCategory.LOW,
                onPause = onPause,
                onCancel = onCancel
            )
        }
    }
}

@Composable
private fun PauseAllRow(
    unifiedExpiryMs: Long?,
    onPauseAll: (Long) -> Unit,
    onCancelAll: () -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.pause_all_alerts),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (unifiedExpiryMs != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                val countdownText = rememberCountdownText(unifiedExpiryMs)
                Text(
                    text = stringResource(R.string.pause_countdown, countdownText),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancelAll) {
                    Text(stringResource(R.string.pause_cancel))
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DURATIONS.forEach { (durationMs, labelRes) ->
                    FilledTonalButton(
                        onClick = { onPauseAll(durationMs) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(stringResource(labelRes), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PauseCategoryRow(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    expiryMs: Long?,
    nowMs: Long,
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

        if (expiryMs != null && expiryMs > nowMs) {
            // Active pause — show countdown + cancel
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                val countdownText = rememberCountdownText(expiryMs)
                Text(
                    text = stringResource(R.string.pause_countdown, countdownText),
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
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DURATIONS.forEach { (durationMs, labelRes) ->
                    FilledTonalButton(
                        onClick = { onPause(category, durationMs) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(stringResource(labelRes), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
