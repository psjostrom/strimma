package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    onGlucoseSourceChange: (GlucoseSource) -> Unit,
    onNightscoutUrlChange: (String) -> Unit,
    onNightscoutSecretChange: (String) -> Unit,
    onFollowerUrlChange: (String) -> Unit,
    onFollowerSecretChange: (String) -> Unit,
    onFollowerPollSecondsChange: (Int) -> Unit,
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

        if (glucoseSource != GlucoseSource.NIGHTSCOUT_FOLLOWER) {
            SettingsSection(stringResource(R.string.settings_source_nightscout_push)) {
                var urlText by remember(nightscoutUrl) { mutableStateOf(nightscoutUrl) }
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text(stringResource(R.string.settings_source_nightscout_url)) },
                    placeholder = { Text(stringResource(R.string.settings_source_url_placeholder)) },
                    supportingText = { Text(stringResource(R.string.settings_source_url_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) onNightscoutUrlChange(urlText) },
                    singleLine = true
                )

                var secretText by remember(nightscoutSecret) { mutableStateOf(nightscoutSecret) }
                OutlinedTextField(
                    value = secretText,
                    onValueChange = { secretText = it },
                    label = { Text(stringResource(R.string.settings_source_api_secret)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) onNightscoutSecretChange(secretText) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        } else {
            SettingsSection(stringResource(R.string.settings_source_following)) {
                var urlText by remember(followerUrl) { mutableStateOf(followerUrl) }
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text(stringResource(R.string.settings_source_nightscout_url)) },
                    placeholder = { Text(stringResource(R.string.settings_source_follower_url_placeholder)) },
                    supportingText = { Text(stringResource(R.string.settings_source_follower_url_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) onFollowerUrlChange(urlText) },
                    singleLine = true
                )

                var secretText by remember(followerSecret) { mutableStateOf(followerSecret) }
                OutlinedTextField(
                    value = secretText,
                    onValueChange = { secretText = it },
                    label = { Text(stringResource(R.string.settings_source_api_secret)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) onFollowerSecretChange(secretText) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
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
