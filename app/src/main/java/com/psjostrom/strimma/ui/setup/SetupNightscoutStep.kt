package com.psjostrom.strimma.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
fun SetupNightscoutStep(
    pushEnabled: Boolean,
    onPushEnabledChange: (Boolean) -> Unit,
    nightscoutUrl: String,
    nightscoutSecret: String,
    onUrlChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    connectionTestState: ConnectionTestState,
    onTestConnection: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.setup_nightscout_toggle),
                    fontSize = 15.sp
                )
                Switch(checked = pushEnabled, onCheckedChange = onPushEnabledChange)
            }
        }

        if (pushEnabled) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    var urlText by remember(nightscoutUrl) { mutableStateOf(nightscoutUrl) }
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = {
                            urlText = it
                            onUrlChange(it)
                        },
                        label = { Text(stringResource(R.string.settings_source_nightscout_url)) },
                        placeholder = { Text(stringResource(R.string.settings_source_url_placeholder)) },
                        supportingText = { Text(stringResource(R.string.settings_source_url_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    var secretText by remember(nightscoutSecret) { mutableStateOf(nightscoutSecret) }
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

                    ConnectionTestButton(
                        testState = connectionTestState,
                        onTest = onTestConnection,
                        hasCredentials = urlText.isNotBlank() && secretText.isNotBlank()
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionTestButton(
    testState: ConnectionTestState,
    onTest: () -> Unit,
    hasCredentials: Boolean = true
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onTest,
            enabled = testState !is ConnectionTestState.Testing && hasCredentials
        ) {
            Text(
                when (testState) {
                    is ConnectionTestState.Testing -> stringResource(R.string.setup_nightscout_testing)
                    else -> stringResource(R.string.setup_nightscout_test)
                }
            )
        }

        when (testState) {
            is ConnectionTestState.Success -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = InRange,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (testState.serverName != null) {
                            stringResource(R.string.setup_nightscout_success_name, testState.serverName)
                        } else {
                            stringResource(R.string.setup_nightscout_success)
                        },
                        color = InRange,
                        fontSize = 13.sp
                    )
                }
            }
            is ConnectionTestState.Failed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.setup_nightscout_failed, testState.error),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
            }
            else -> {}
        }
    }
}
