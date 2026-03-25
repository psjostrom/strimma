package com.psjostrom.strimma.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.ui.theme.InRange

@Composable
fun SetupPermissionsStep(
    isNotificationPermissionGranted: Boolean,
    isBatteryOptimizationIgnored: Boolean,
    isNotificationAccessGranted: Boolean,
    showNotificationAccess: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    onStart: () -> Unit
) {
    val allCriticalGranted = isNotificationPermissionGranted &&
            (!showNotificationAccess || isNotificationAccessGranted)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PermissionRow(
                    label = stringResource(R.string.setup_permissions_notifications),
                    description = stringResource(R.string.setup_permissions_notifications_desc),
                    isGranted = isNotificationPermissionGranted,
                    onGrant = onRequestNotificationPermission
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                PermissionRow(
                    label = stringResource(R.string.setup_permissions_battery),
                    description = stringResource(R.string.setup_permissions_battery_desc),
                    isGranted = isBatteryOptimizationIgnored,
                    onGrant = onRequestBatteryOptimization
                )

                if (showNotificationAccess) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    PermissionRow(
                        label = stringResource(R.string.setup_permissions_notif_access),
                        description = stringResource(R.string.setup_permissions_notif_access_desc),
                        isGranted = isNotificationAccessGranted,
                        onGrant = onRequestNotificationAccess
                    )
                }
            }
        }

        if (!isBatteryOptimizationIgnored) {
            Text(
                stringResource(R.string.setup_permissions_battery_warning),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onStart,
            enabled = allCriticalGranted,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                stringResource(R.string.setup_start),
                fontSize = 16.sp
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    description: String,
    isGranted: Boolean,
    onGrant: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, fontSize = 14.sp)
            Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
        if (isGranted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = InRange,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.setup_permissions_granted),
                    color = InRange,
                    fontSize = 13.sp
                )
            }
        } else {
            OutlinedButton(onClick = onGrant) {
                Text(stringResource(R.string.setup_permissions_grant))
            }
        }
    }
}
