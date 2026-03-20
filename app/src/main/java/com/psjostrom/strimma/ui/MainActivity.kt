package com.psjostrom.strimma.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.app.AlertDialog
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.psjostrom.strimma.network.FollowerStatus
import com.psjostrom.strimma.receiver.GlucoseNotificationListener
import com.psjostrom.strimma.service.StrimmaService
import com.psjostrom.strimma.ui.settings.*
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import com.psjostrom.strimma.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var permissionsChecked = false
    private var viewModelRef: MainViewModel? = null

    private val importSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    ?: throw IllegalStateException("Could not read file")
                viewModelRef?.importSettings(json)
                Toast.makeText(this@MainActivity, "Settings imported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Service starts regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only prompt permissions once per process, not on every onCreate
        if (!permissionsChecked) {
            permissionsChecked = true

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }

            if (!GlucoseNotificationListener.isEnabled(this)) {
                GlucoseNotificationListener.openSettings(this)
            }
        }

        startForegroundService(Intent(this, StrimmaService::class.java))

        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            viewModelRef = viewModel
            val themeModeStr by viewModel.themeMode.collectAsState()
            val themeMode = try { ThemeMode.valueOf(themeModeStr) } catch (_: Exception) { ThemeMode.System }

            StrimmaTheme(themeMode = themeMode) {
                val navController = rememberNavController()

                val latestReading by viewModel.latestReading.collectAsState()
                val readings by viewModel.readings.collectAsState()
                val bgLow by viewModel.bgLow.collectAsState()
                val bgHigh by viewModel.bgHigh.collectAsState()
                val graphWindowHours by viewModel.graphWindowHours.collectAsState()
                val nightscoutUrl by viewModel.nightscoutUrl.collectAsState()
                val alertLowEnabled by viewModel.alertLowEnabled.collectAsState()
                val alertHighEnabled by viewModel.alertHighEnabled.collectAsState()
                val alertUrgentLowEnabled by viewModel.alertUrgentLowEnabled.collectAsState()
                val alertLow by viewModel.alertLow.collectAsState()
                val alertHigh by viewModel.alertHigh.collectAsState()
                val alertUrgentLow by viewModel.alertUrgentLow.collectAsState()
                val alertUrgentHighEnabled by viewModel.alertUrgentHighEnabled.collectAsState()
                val alertUrgentHigh by viewModel.alertUrgentHigh.collectAsState()
                val alertStaleEnabled by viewModel.alertStaleEnabled.collectAsState()
                val alertLowSoonEnabled by viewModel.alertLowSoonEnabled.collectAsState()
                val alertHighSoonEnabled by viewModel.alertHighSoonEnabled.collectAsState()
                val notifGraphMinutes by viewModel.notifGraphMinutes.collectAsState()
                val predictionMinutes by viewModel.predictionMinutes.collectAsState()
                val glucoseUnit by viewModel.glucoseUnit.collectAsState()
                val bgBroadcastEnabled by viewModel.bgBroadcastEnabled.collectAsState()
                val glucoseSource by viewModel.glucoseSource.collectAsState()
                val followerStatus by viewModel.followerStatus.collectAsState()
                val followerUrl by viewModel.followerUrl.collectAsState()
                val followerPollSeconds by viewModel.followerPollSeconds.collectAsState()
                val treatmentsSyncEnabled by viewModel.treatmentsSyncEnabled.collectAsState()
                val insulinType by viewModel.insulinType.collectAsState()
                val customDIA by viewModel.customDIA.collectAsState()
                val treatments by viewModel.treatments.collectAsState()
                val iob by viewModel.iob.collectAsState()

                NavHost(navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            latestReading = latestReading,
                            readings = readings,
                            bgLow = bgLow,
                            bgHigh = bgHigh,
                            graphWindowHours = graphWindowHours,
                            predictionMinutes = predictionMinutes,
                            glucoseUnit = glucoseUnit,
                            followerStatus = followerStatus,
                            treatments = treatments,
                            iob = iob,
                            onSettingsClick = {
                                navController.navigate("settings") {
                                    launchSingleTop = true
                                }
                            },
                            onStatsClick = {
                                navController.navigate("stats") {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onNavigate = { route ->
                                navController.navigate(route) {
                                    launchSingleTop = true
                                }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings/data-source") {
                        DataSourceSettings(
                            glucoseSource = glucoseSource,
                            nightscoutUrl = nightscoutUrl,
                            nightscoutSecret = viewModel.nightscoutSecret,
                            followerUrl = followerUrl,
                            followerSecret = viewModel.followerSecret,
                            followerPollSeconds = followerPollSeconds,
                            onGlucoseSourceChange = viewModel::setGlucoseSource,
                            onNightscoutUrlChange = viewModel::setNightscoutUrl,
                            onNightscoutSecretChange = viewModel::setNightscoutSecret,
                            onFollowerUrlChange = viewModel::setFollowerUrl,
                            onFollowerSecretChange = viewModel::setFollowerSecret,
                            onFollowerPollSecondsChange = viewModel::setFollowerPollSeconds,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings/treatments") {
                        TreatmentsSettings(
                            treatmentsSyncEnabled = treatmentsSyncEnabled,
                            insulinType = insulinType,
                            customDIA = customDIA,
                            onTreatmentsSyncEnabledChange = viewModel::setTreatmentsSyncEnabled,
                            onInsulinTypeChange = viewModel::setInsulinType,
                            onCustomDIAChange = viewModel::setCustomDIA,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings/display") {
                        DisplaySettings(
                            glucoseUnit = glucoseUnit,
                            graphWindowHours = graphWindowHours,
                            bgLow = bgLow,
                            bgHigh = bgHigh,
                            themeMode = themeModeStr,
                            onGlucoseUnitChange = viewModel::setGlucoseUnit,
                            onGraphWindowChange = viewModel::setGraphWindowHours,
                            onBgLowChange = viewModel::setBgLow,
                            onBgHighChange = viewModel::setBgHigh,
                            onThemeModeChange = viewModel::setThemeMode,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings/notifications") {
                        NotificationSettings(
                            notifGraphMinutes = notifGraphMinutes,
                            predictionMinutes = predictionMinutes,
                            onNotifGraphMinutesChange = viewModel::setNotifGraphMinutes,
                            onPredictionMinutesChange = viewModel::setPredictionMinutes,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings/alerts") {
                        AlertsSettings(
                            glucoseUnit = glucoseUnit,
                            alertLowEnabled = alertLowEnabled,
                            alertHighEnabled = alertHighEnabled,
                            alertUrgentLowEnabled = alertUrgentLowEnabled,
                            alertUrgentHighEnabled = alertUrgentHighEnabled,
                            alertLow = alertLow,
                            alertHigh = alertHigh,
                            alertUrgentLow = alertUrgentLow,
                            alertUrgentHigh = alertUrgentHigh,
                            alertStaleEnabled = alertStaleEnabled,
                            alertLowSoonEnabled = alertLowSoonEnabled,
                            alertHighSoonEnabled = alertHighSoonEnabled,
                            onAlertLowEnabledChange = viewModel::setAlertLowEnabled,
                            onAlertHighEnabledChange = viewModel::setAlertHighEnabled,
                            onAlertUrgentLowEnabledChange = viewModel::setAlertUrgentLowEnabled,
                            onAlertUrgentHighEnabledChange = viewModel::setAlertUrgentHighEnabled,
                            onAlertLowChange = viewModel::setAlertLow,
                            onAlertHighChange = viewModel::setAlertHigh,
                            onAlertUrgentLowChange = viewModel::setAlertUrgentLow,
                            onAlertUrgentHighChange = viewModel::setAlertUrgentHigh,
                            onAlertStaleEnabledChange = viewModel::setAlertStaleEnabled,
                            onAlertLowSoonEnabledChange = viewModel::setAlertLowSoonEnabled,
                            onAlertHighSoonEnabledChange = viewModel::setAlertHighSoonEnabled,
                            onOpenAlertSound = viewModel::openAlertChannelSettings,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings/data") {
                        DataSettings(
                            bgBroadcastEnabled = bgBroadcastEnabled,
                            onBgBroadcastEnabledChange = viewModel::setBgBroadcastEnabled,
                            onStats = {
                                navController.navigate("stats") {
                                    launchSingleTop = true
                                }
                            },
                            onWidgetSettings = {
                                startActivity(Intent(this@MainActivity, com.psjostrom.strimma.widget.WidgetConfigActivity::class.java).apply {
                                    putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
                                })
                            },
                            onExportSettings = {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Export Settings")
                                    .setMessage("The export file contains your Nightscout secrets in plain text. Only share it with apps you trust.")
                                    .setPositiveButton("Export") { _, _ ->
                                        lifecycleScope.launch {
                                            try {
                                                val json = viewModel.exportSettings()
                                                val file = File(cacheDir, "strimma-settings.json")
                                                file.delete()
                                                file.writeText(json)
                                                val uri = FileProvider.getUriForFile(
                                                    this@MainActivity,
                                                    "${packageName}.fileprovider",
                                                    file
                                                )
                                                startActivity(Intent.createChooser(
                                                    Intent(Intent.ACTION_SEND).apply {
                                                        type = "application/json"
                                                        putExtra(Intent.EXTRA_STREAM, uri)
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    },
                                                    "Export Settings"
                                                ))
                                            } catch (e: Exception) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Export failed: ${e.message}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            },
                            onImportSettings = {
                                importSettingsLauncher.launch("*/*")
                            },
                            onPullFromNightscout = { days ->
                                lifecycleScope.launch {
                                    Toast.makeText(this@MainActivity, "Pulling $days days from Nightscout…", Toast.LENGTH_SHORT).show()
                                    val result = viewModel.pullFromNightscout(days)
                                    result.onSuccess { count ->
                                        Toast.makeText(this@MainActivity, "Pulled $count readings", Toast.LENGTH_SHORT).show()
                                    }.onFailure { e ->
                                        Toast.makeText(this@MainActivity, "Pull failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("stats") {
                        StatsScreen(
                            bgLow = bgLow,
                            bgHigh = bgHigh,
                            glucoseUnit = glucoseUnit,
                            onLoadReadings = viewModel::readingsForPeriod,
                            onExportCsv = viewModel::exportCsv,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("debug") {
                        DebugScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
