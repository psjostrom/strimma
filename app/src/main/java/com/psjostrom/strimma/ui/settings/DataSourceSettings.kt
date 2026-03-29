package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseSource

@Composable
fun DataSourceSettings(
    glucoseSource: GlucoseSource,
    nightscoutUrl: String,
    nightscoutSecret: String,
    followerUrl: String,
    followerSecret: String,
    followerPollSeconds: Int,
    lluEmail: String,
    lluPassword: String,
    isNotificationAccessGranted: Boolean,
    onGlucoseSourceChange: (GlucoseSource) -> Unit,
    onNightscoutUrlChange: (String) -> Unit,
    onNightscoutSecretChange: (String) -> Unit,
    onFollowerUrlChange: (String) -> Unit,
    onFollowerSecretChange: (String) -> Unit,
    onFollowerPollSecondsChange: (Int) -> Unit,
    onLluEmailChange: (String) -> Unit,
    onLluPasswordChange: (String) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onPullFromNightscout: (Int) -> Unit,
    onBack: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline

    SettingsScaffold(title = stringResource(R.string.settings_source_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.settings_source_section)) {
            GlucoseSource.entries.forEach { source ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = glucoseSource == source,
                        onClick = { onGlucoseSourceChange(source) }
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(stringResource(source.labelRes), color = onBg, fontSize = 14.sp)
                        Text(stringResource(source.descriptionRes), color = outline, fontSize = 12.sp)
                    }
                }
            }
        }

        if (glucoseSource == GlucoseSource.COMPANION) {
            SettingsSection(stringResource(R.string.settings_source_notification_access)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (!isNotificationAccessGranted) {
                                Modifier.clickable(onClick = onOpenNotificationAccess)
                            } else Modifier
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            stringResource(R.string.settings_source_notification_access),
                            color = onBg,
                            fontSize = 14.sp
                        )
                        Text(
                            if (isNotificationAccessGranted) {
                                stringResource(R.string.settings_source_notification_granted)
                            } else {
                                stringResource(R.string.settings_source_notification_not_granted)
                            },
                            color = if (isNotificationAccessGranted) outline else MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                    if (!isNotificationAccessGranted) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = outline,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        if (glucoseSource == GlucoseSource.LIBRELINKUP) {
            SettingsSection(stringResource(R.string.settings_source_llu_section)) {
                var emailText by remember(lluEmail) { mutableStateOf(lluEmail) }
                OutlinedTextField(
                    value = emailText,
                    onValueChange = {
                        emailText = it
                        onLluEmailChange(it)
                    },
                    label = { Text(stringResource(R.string.settings_source_llu_email)) },
                    placeholder = { Text(stringResource(R.string.settings_source_llu_email_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                var passwordText by remember(lluPassword) { mutableStateOf(lluPassword) }
                OutlinedTextField(
                    value = passwordText,
                    onValueChange = {
                        passwordText = it
                        onLluPasswordChange(it)
                    },
                    label = { Text(stringResource(R.string.settings_source_llu_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                )

                Text(
                    stringResource(R.string.settings_source_llu_hint),
                    color = outline,
                    fontSize = 12.sp
                )
            }
        }

        PullDataSection(
            title = stringResource(R.string.settings_data_pull),
            description = stringResource(R.string.settings_data_pull_desc),
            onPull = onPullFromNightscout
        )

        if (glucoseSource != GlucoseSource.NIGHTSCOUT_FOLLOWER) {
            SettingsSection(stringResource(R.string.settings_source_nightscout_push)) {
                var urlText by remember(nightscoutUrl) { mutableStateOf(nightscoutUrl) }
                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        onNightscoutUrlChange(it)
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
                        onNightscoutSecretChange(it)
                    },
                    label = { Text(stringResource(R.string.settings_source_api_secret)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        } else {
            SettingsSection(stringResource(R.string.settings_source_following)) {
                var urlText by remember(followerUrl) { mutableStateOf(followerUrl) }
                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        onFollowerUrlChange(it)
                    },
                    label = { Text(stringResource(R.string.settings_source_nightscout_url)) },
                    placeholder = { Text(stringResource(R.string.settings_source_follower_url_placeholder)) },
                    supportingText = { Text(stringResource(R.string.settings_source_follower_url_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                var secretText by remember(followerSecret) { mutableStateOf(followerSecret) }
                OutlinedTextField(
                    value = secretText,
                    onValueChange = {
                        secretText = it
                        onFollowerSecretChange(it)
                    },
                    label = { Text(stringResource(R.string.settings_source_api_secret)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text(
                    stringResource(R.string.settings_source_poll_interval, followerPollSeconds),
                    color = onBg,
                    fontSize = 14.sp
                )
                Text(
                    stringResource(R.string.settings_source_poll_explanation),
                    color = outline,
                    fontSize = 12.sp
                )
                Slider(
                    value = followerPollSeconds.toFloat(),
                    onValueChange = { onFollowerPollSecondsChange(it.toInt()) },
                    valueRange = 30f..300f,
                    steps = 8
                )
            }
        }
    }
}
