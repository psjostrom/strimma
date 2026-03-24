@file:Suppress("MatchingDeclarationName") // ViewModel is internal to the ExerciseSettings screen

package com.psjostrom.strimma.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.health.HealthConnectManager
import com.psjostrom.strimma.data.health.HealthConnectStatus
import com.psjostrom.strimma.ui.theme.AboveHigh
import com.psjostrom.strimma.ui.theme.InRange
import com.psjostrom.strimma.ui.theme.Stale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class ExerciseSettingsViewModel @Inject constructor(
    val healthConnectManager: HealthConnectManager,
    private val settings: SettingsRepository
) : ViewModel() {

    val hcWriteEnabled: StateFlow<Boolean> = settings.hcWriteEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val hcLastSync: StateFlow<Long> = settings.hcLastSync
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _hasPermissions = MutableStateFlow(false)
    val hasPermissions: StateFlow<Boolean> = _hasPermissions

    init {
        viewModelScope.launch {
            _hasPermissions.value = healthConnectManager.hasPermissions()
        }
    }

    fun setHcWriteEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setHcWriteEnabled(enabled) }
    }

    fun refreshPermissions() {
        viewModelScope.launch {
            _hasPermissions.value = healthConnectManager.hasPermissions()
        }
    }
}

@Composable
fun ExerciseSettings(
    onBack: () -> Unit,
    viewModel: ExerciseSettingsViewModel = hiltViewModel()
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline
    val context = LocalContext.current

    val hcStatus = remember { viewModel.healthConnectManager.isAvailable() }
    val hasPermissions by viewModel.hasPermissions.collectAsState()
    val hcWriteEnabled by viewModel.hcWriteEnabled.collectAsState()
    val hcLastSync by viewModel.hcLastSync.collectAsState()

    val permissionContract = remember { viewModel.healthConnectManager.createPermissionContract() }
    val permissionLauncher = permissionContract?.let { contract ->
        rememberLauncherForActivityResult(contract) {
            viewModel.refreshPermissions()
        }
    }

    SettingsScaffold(title = stringResource(R.string.exercise_settings_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.exercise_hc_status_label)) {
            // HC status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    val (dotColor, statusText) = when {
                        hcStatus == HealthConnectStatus.NOT_SUPPORTED ->
                            Stale to stringResource(R.string.exercise_hc_status_not_supported)
                        hcStatus == HealthConnectStatus.NOT_INSTALLED ->
                            Stale to stringResource(R.string.exercise_hc_status_not_installed)
                        hasPermissions ->
                            InRange to stringResource(R.string.exercise_hc_status_connected)
                        else ->
                            AboveHigh to stringResource(R.string.exercise_hc_status_permissions)
                    }

                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(statusText, color = onBg, fontSize = 14.sp)
                }

                when {
                    hcStatus == HealthConnectStatus.NOT_INSTALLED -> {
                        TextButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = "market://details?id=com.google.android.apps.healthdata".toUri()
                            }
                            context.startActivity(intent)
                        }) {
                            Text(
                                stringResource(R.string.exercise_hc_install_prompt),
                                color = InRange,
                                fontSize = 13.sp
                            )
                        }
                    }
                    hcStatus == HealthConnectStatus.AVAILABLE && !hasPermissions -> {
                        TextButton(onClick = {
                            permissionLauncher?.launch(viewModel.healthConnectManager.permissions)
                        }) {
                            Text(
                                stringResource(R.string.common_ok),
                                color = InRange,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // Write toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        stringResource(R.string.exercise_hc_write_toggle),
                        color = onBg,
                        fontSize = 14.sp
                    )
                    Text(
                        stringResource(R.string.exercise_hc_write_subtitle),
                        color = outline,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = hcWriteEnabled,
                    onCheckedChange = viewModel::setHcWriteEnabled,
                    enabled = hcStatus == HealthConnectStatus.AVAILABLE && hasPermissions
                )
            }

            // Last sync
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.exercise_hc_last_sync),
                    color = onBg,
                    fontSize = 14.sp
                )
                val syncText = if (hcLastSync == 0L) {
                    stringResource(R.string.exercise_hc_last_sync_never)
                } else {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(hcLastSync))
                }
                Text(syncText, color = outline, fontSize = 14.sp)
            }
        }
    }
}
