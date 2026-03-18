package com.psjostrom.strimma.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.psjostrom.strimma.receiver.GlucoseNotificationListener
import com.psjostrom.strimma.service.StrimmaService
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import com.psjostrom.strimma.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var permissionsChecked = false

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
            val themeModeStr by viewModel.themeMode.collectAsState()
            val themeMode = try { ThemeMode.valueOf(themeModeStr) } catch (_: Exception) { ThemeMode.System }

            StrimmaTheme(themeMode = themeMode) {
                val navController = rememberNavController()

                val latestReading by viewModel.latestReading.collectAsState()
                val readings by viewModel.readings.collectAsState()
                val bgLow by viewModel.bgLow.collectAsState()
                val bgHigh by viewModel.bgHigh.collectAsState()
                val graphWindowHours by viewModel.graphWindowHours.collectAsState()
                val springaUrl by viewModel.springaUrl.collectAsState()
                val alertLowEnabled by viewModel.alertLowEnabled.collectAsState()
                val alertHighEnabled by viewModel.alertHighEnabled.collectAsState()
                val alertUrgentLowEnabled by viewModel.alertUrgentLowEnabled.collectAsState()
                val alertLow by viewModel.alertLow.collectAsState()
                val alertHigh by viewModel.alertHigh.collectAsState()
                val alertUrgentLow by viewModel.alertUrgentLow.collectAsState()
                val alertUrgentHighEnabled by viewModel.alertUrgentHighEnabled.collectAsState()
                val alertUrgentHigh by viewModel.alertUrgentHigh.collectAsState()
                val alertStaleEnabled by viewModel.alertStaleEnabled.collectAsState()

                NavHost(navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            latestReading = latestReading,
                            readings = readings,
                            bgLow = bgLow,
                            bgHigh = bgHigh,
                            graphWindowHours = graphWindowHours,
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
                            springaUrl = springaUrl,
                            apiSecret = viewModel.apiSecret,
                            graphWindowHours = graphWindowHours,
                            bgLow = bgLow,
                            bgHigh = bgHigh,
                            themeMode = themeModeStr,
                            alertLowEnabled = alertLowEnabled,
                            alertHighEnabled = alertHighEnabled,
                            alertUrgentLowEnabled = alertUrgentLowEnabled,
                            alertUrgentHighEnabled = alertUrgentHighEnabled,
                            alertLow = alertLow,
                            alertHigh = alertHigh,
                            alertUrgentLow = alertUrgentLow,
                            alertUrgentHigh = alertUrgentHigh,
                            alertStaleEnabled = alertStaleEnabled,
                            onSpringaUrlChange = viewModel::setSpringaUrl,
                            onApiSecretChange = viewModel::setApiSecret,
                            onGraphWindowChange = viewModel::setGraphWindowHours,
                            onBgLowChange = viewModel::setBgLow,
                            onBgHighChange = viewModel::setBgHigh,
                            onThemeModeChange = viewModel::setThemeMode,
                            onAlertLowEnabledChange = viewModel::setAlertLowEnabled,
                            onAlertHighEnabledChange = viewModel::setAlertHighEnabled,
                            onAlertUrgentLowEnabledChange = viewModel::setAlertUrgentLowEnabled,
                            onAlertUrgentHighEnabledChange = viewModel::setAlertUrgentHighEnabled,
                            onAlertLowChange = viewModel::setAlertLow,
                            onAlertHighChange = viewModel::setAlertHigh,
                            onAlertUrgentLowChange = viewModel::setAlertUrgentLow,
                            onAlertUrgentHighChange = viewModel::setAlertUrgentHigh,
                            onAlertStaleEnabledChange = viewModel::setAlertStaleEnabled,
                            onOpenAlertSound = viewModel::openAlertChannelSettings,
                            onBack = { navController.popBackStack() },
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
                            onDebugLog = {
                                navController.navigate("debug") {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                    composable("stats") {
                        StatsScreen(
                            bgLow = bgLow,
                            bgHigh = bgHigh,
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
