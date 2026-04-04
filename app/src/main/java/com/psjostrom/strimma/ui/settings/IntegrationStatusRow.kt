package com.psjostrom.strimma.ui.settings

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.network.IntegrationStatus
import com.psjostrom.strimma.ui.theme.InRange

@Composable
fun IntegrationStatusRow(
    status: IntegrationStatus,
    @StringRes activityLabelRes: Int
) {
    when (status) {
        is IntegrationStatus.Idle -> {}
        is IntegrationStatus.Connecting -> {
            Text(
                stringResource(R.string.integration_connecting),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        is IntegrationStatus.Connected -> {
            val relative = DateUtils.getRelativeTimeSpanString(
                status.lastActivityTs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            Text(
                stringResource(R.string.integration_connected, stringResource(activityLabelRes), relative),
                color = InRange,
                fontSize = 12.sp
            )
        }
        is IntegrationStatus.Error -> {
            Text(
                status.message,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp
            )
        }
    }
}
