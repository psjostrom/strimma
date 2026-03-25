package com.psjostrom.strimma.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.ui.theme.InRange

@Composable
fun SetupWelcomeStep() {
    Column(
        modifier = Modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.setup_welcome_body),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onBackground,
            lineHeight = 22.sp
        )

        Spacer(Modifier.height(8.dp))

        WelcomeItem(Icons.Default.ShowChart, stringResource(R.string.setup_welcome_item_source))
        WelcomeItem(Icons.Default.Sync, stringResource(R.string.setup_welcome_item_nightscout))
        WelcomeItem(Icons.Default.Notifications, stringResource(R.string.setup_welcome_item_alerts))
        WelcomeItem(Icons.Default.Settings, stringResource(R.string.setup_welcome_item_permissions))
    }
}

@Composable
private fun WelcomeItem(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = InRange,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
