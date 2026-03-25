package com.psjostrom.strimma.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseSource
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import com.psjostrom.strimma.ui.theme.InRange

@Composable
fun SetupDataSourceStep(
    selectedSource: GlucoseSource,
    onSourceChange: (GlucoseSource) -> Unit,
    isNotificationAccessGranted: Boolean,
    onOpenNotificationAccess: () -> Unit,
    onOpenAppInfo: () -> Unit,
    // Follower fields (inline when NIGHTSCOUT_FOLLOWER selected)
    followerUrl: String,
    followerSecret: String,
    followerTestState: ConnectionTestState,
    onFollowerUrlChange: (String) -> Unit,
    onFollowerSecretChange: (String) -> Unit,
    onTestFollowerConnection: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SourceCard(
            label = stringResource(R.string.settings_source_companion_label),
            description = stringResource(R.string.setup_source_companion_desc),
            selected = selectedSource == GlucoseSource.COMPANION,
            onClick = { onSourceChange(GlucoseSource.COMPANION) }
        )
        SourceCard(
            label = stringResource(R.string.settings_source_xdrip_label),
            description = stringResource(R.string.setup_source_xdrip_desc),
            selected = selectedSource == GlucoseSource.XDRIP_BROADCAST,
            onClick = { onSourceChange(GlucoseSource.XDRIP_BROADCAST) }
        )
        SourceCard(
            label = stringResource(R.string.settings_source_follower_label),
            description = stringResource(R.string.setup_source_follower_desc),
            selected = selectedSource == GlucoseSource.NIGHTSCOUT_FOLLOWER,
            onClick = { onSourceChange(GlucoseSource.NIGHTSCOUT_FOLLOWER) }
        )

        if (selectedSource == GlucoseSource.COMPANION) {
            NotificationAccessGuide(
                isGranted = isNotificationAccessGranted,
                onOpenAppInfo = onOpenAppInfo,
                onOpenNotificationAccess = onOpenNotificationAccess
            )
        }

        if (selectedSource == GlucoseSource.NIGHTSCOUT_FOLLOWER) {
            FollowerConfigBlock(
                url = followerUrl,
                secret = followerSecret,
                testState = followerTestState,
                onUrlChange = onFollowerUrlChange,
                onSecretChange = onFollowerSecretChange,
                onTestConnection = onTestFollowerConnection
            )
        }
    }
}

@Composable
private fun SourceCard(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) InRange else MaterialTheme.colorScheme.outlineVariant

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun NotificationAccessGuide(
    isGranted: Boolean,
    onOpenAppInfo: () -> Unit,
    onOpenNotificationAccess: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isGranted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = InRange,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.setup_source_access_granted),
                        color = InRange,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Text(
                    stringResource(R.string.setup_source_notif_guide_intro),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline,
                    lineHeight = 18.sp
                )

                val context = LocalContext.current
                val learnMoreUrl = stringResource(R.string.setup_source_notif_learn_more_url)
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, learnMoreUrl.toUri()))
                        } catch (_: ActivityNotFoundException) { /* No browser available */ }
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        stringResource(R.string.setup_source_notif_learn_more),
                        color = InRange,
                        fontSize = 13.sp
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Step 1: Try to enable (will be blocked)
                NumberedStep(
                    number = 1,
                    text = stringResource(R.string.setup_source_guide_step1)
                )
                OutlinedButton(
                    onClick = onOpenNotificationAccess,
                    modifier = Modifier.padding(start = 38.dp)
                ) {
                    Text(stringResource(R.string.setup_source_guide_step1_button), fontSize = 13.sp)
                }

                // Step 2: Allow restricted settings
                NumberedStep(
                    number = 2,
                    text = stringResource(R.string.setup_source_guide_step2)
                )
                OutlinedButton(
                    onClick = onOpenAppInfo,
                    modifier = Modifier.padding(start = 38.dp)
                ) {
                    Text(stringResource(R.string.setup_source_step1_button), fontSize = 13.sp)
                }

                // Step 3: Enable for real
                NumberedStep(
                    number = 3,
                    text = stringResource(R.string.setup_source_guide_step3)
                )
                OutlinedButton(
                    onClick = onOpenNotificationAccess,
                    modifier = Modifier.padding(start = 38.dp)
                ) {
                    Text(stringResource(R.string.setup_source_guide_step3_button), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun NumberedStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    "$number",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.surface
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun FollowerConfigBlock(
    url: String,
    secret: String,
    testState: ConnectionTestState,
    onUrlChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onTestConnection: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            var urlText by remember(url) { mutableStateOf(url) }
            OutlinedTextField(
                value = urlText,
                onValueChange = {
                    urlText = it
                    onUrlChange(it)
                },
                label = { Text(stringResource(R.string.settings_source_nightscout_url)) },
                placeholder = { Text(stringResource(R.string.settings_source_follower_url_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            var secretText by remember(secret) { mutableStateOf(secret) }
            OutlinedTextField(
                value = secretText,
                onValueChange = {
                    secretText = it
                    onSecretChange(it)
                },
                label = { Text(stringResource(R.string.settings_source_api_secret)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            ConnectionTestButton(testState = testState, onTest = onTestConnection)
        }
    }
}
