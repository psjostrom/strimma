package com.psjostrom.strimma.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import android.os.PowerManager
import android.provider.Settings
import android.app.AlertDialog
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.IOBComputer
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
import com.psjostrom.strimma.ui.settings.AlertsSettings
import com.psjostrom.strimma.ui.settings.DataSettings
import com.psjostrom.strimma.ui.settings.DataSourceSettings
import com.psjostrom.strimma.ui.settings.GeneralSettings
import com.psjostrom.strimma.ui.settings.DisplaySettings
import com.psjostrom.strimma.ui.settings.NotificationSettings
import com.psjostrom.strimma.ui.settings.TreatmentsSettings
import com.psjostrom.strimma.ui.setup.SetupScreen
import com.psjostrom.strimma.ui.setup.SetupViewModel
import com.psjostrom.strimma.ui.setup.defaultUnitForLocale
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import com.psjostrom.strimma.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File

private const val EXPORT_HOURS_30_DAYS = 720

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var permissionsChecked = false
    private var viewModelRef: MainViewModel? = null

    private fun pullWithToast(
        days: Int,
        progressResId: Int,
        successResId: Int,
        failureResId: Int,
        operation: suspend (Int) -> Result<Int>
    ) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, getString(progressResId, days), Toast.LENGTH_SHORT).show()
            operation(days)
                .onSuccess { count ->
                    Toast.makeText(this@MainActivity, getString(successResId, count), Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    Toast.makeText(this@MainActivity, getString(failureResId, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
        }
    }

    private val importSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    ?: error("Could not read file")
                viewModelRef?.importSettings(json)
                Toast.makeText(this@MainActivity, getString(R.string.activity_settings_imported), Toast.LENGTH_SHORT).show()
            } catch (
                @Suppress("TooGenericExceptionCaught") // File I/O + JSON parsing — multiple exception types possible
                e: Exception
            ) {
                Toast.makeText(this@MainActivity, getString(R.string.activity_import_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Service starts regardless */ }

    @SuppressLint("BatteryLife") // CGM safety app — acceptable per Android Doze docs
    private fun startServiceIfSetupDone() {
        startForegroundService(Intent(this, StrimmaService::class.java))

        // Only prompt permissions once per process for existing users
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
                    data = "package:$packageName".toUri()
                })
            }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // Compose setContent wiring
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            viewModelRef = viewModel
            val setupCompleted by viewModel.setupCompleted.collectAsState()
            val themeModeStr by viewModel.themeMode.collectAsState()
            val themeMode = try { ThemeMode.valueOf(themeModeStr) } catch (_: Exception) { ThemeMode.System }

            // Wait for DataStore to load before showing anything
            if (setupCompleted == null) return@setContent

            // Start service once we know setup is done
            LaunchedEffect(setupCompleted) {
                if (setupCompleted == true) startServiceIfSetupDone()
            }

            StrimmaTheme(themeMode = themeMode) {
                val navController = rememberNavController()
                val startDest = if (setupCompleted == true) "main" else "setup"

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
                val hbA1cUnit by viewModel.hbA1cUnit.collectAsState()
                val bgBroadcastEnabled by viewModel.bgBroadcastEnabled.collectAsState()
                val glucoseSource by viewModel.glucoseSource.collectAsState()
                val followerStatus by viewModel.followerStatus.collectAsState()
                val followerPollSeconds by viewModel.followerPollSeconds.collectAsState()
                val nightscoutConfigured by viewModel.nightscoutConfigured.collectAsState()
                val treatmentsSyncEnabled by viewModel.treatmentsSyncEnabled.collectAsState()
                val insulinType by viewModel.insulinType.collectAsState()
                val customDIA by viewModel.customDIA.collectAsState()
                val treatments by viewModel.treatments.collectAsState()
                val iob by viewModel.iob.collectAsState()
                val exerciseSessions by viewModel.exerciseSessions.collectAsState()
                val guidanceState by viewModel.guidanceState.collectAsState()
                val pauseLowExpiryMs by viewModel.pauseLowExpiryMs.collectAsState()
                val pauseHighExpiryMs by viewModel.pauseHighExpiryMs.collectAsState()
                NavHost(navController, startDestination = startDest) {
                    composable("setup") {
                        val setupViewModel: SetupViewModel = hiltViewModel()
                        val setupStep by viewModel.setupStep.collectAsState()
                        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                        val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
                        val isNotifAccessGranted = remember(lifecycleState) {
                            GlucoseNotificationListener.isEnabled(this@MainActivity)
                        }
                        val isNotifPermGranted = remember(lifecycleState) {
                            ContextCompat.checkSelfPermission(
                                this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                        }
                        val isBatteryIgnored = remember(lifecycleState) {
                            getSystemService(PowerManager::class.java)
                                .isIgnoringBatteryOptimizations(packageName)
                        }

                        // Set locale-based default unit only on fresh wizard start
                        LaunchedEffect(setupStep) {
                            if (setupStep == 0) {
                                setupViewModel.setGlucoseUnit(defaultUnitForLocale())
                            }
                        }

                        SetupScreen(
                            viewModel = setupViewModel,
                            initialStep = setupStep,
                            isNotificationAccessGranted = isNotifAccessGranted,
                            isNotificationPermissionGranted = isNotifPermGranted,
                            isBatteryOptimizationIgnored = isBatteryIgnored,
                            onRequestNotificationPermission = {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            },
                            onRequestBatteryOptimization = {
                                @SuppressLint("BatteryLife")
                                val intent = Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                ).apply { data = "package:$packageName".toUri() }
                                startActivity(intent)
                            },
                            onOpenNotificationAccess = {
                                GlucoseNotificationListener.openSettings(this@MainActivity)
                            },
                            onOpenAppInfo = {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = "package:$packageName".toUri()
                                })
                            },
                            onSetupComplete = {
                                startServiceIfSetupDone()
                                navController.navigate("main") {
                                    popUpTo("setup") { inclusive = true }
                                }
                            }
                        )
                    }
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
                            iobTauMinutes = IOBComputer.tauForInsulinType(insulinType, customDIA),
                            exerciseSessions = exerciseSessions,
                            guidanceState = guidanceState,
                            pauseLowExpiryMs = pauseLowExpiryMs,
                            pauseHighExpiryMs = pauseHighExpiryMs,
                            onPauseAlerts = viewModel::pauseAlerts,
                            onCancelPause = viewModel::cancelAlertPause,
                            onComputeBGContext = viewModel::computeExerciseBGContext,
                            onSettingsClick = {
                                navController.navigate("settings") {
                                    launchSingleTop = true
                                }
                            },
                            onExerciseClick = {
                                navController.navigate("exercise") {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                    composable("exercise") {
                        ExerciseHistoryScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToExerciseSettings = {
                                navController.navigate("settings/exercise") {
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
                            onBack = { navController.popBackStack() },
                            nightscoutConfigured = nightscoutConfigured
                        )
                    }
                    composable("settings/data-source") {
                        val dsLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                        val dsLifecycleState by dsLifecycleOwner.lifecycle.currentStateFlow.collectAsState()
                        val isNotifAccessGranted = remember(dsLifecycleState) {
                            GlucoseNotificationListener.isEnabled(this@MainActivity)
                        }
                        DataSourceSettings(
                            glucoseSource = glucoseSource,
                            nightscoutUrl = nightscoutUrl,
                            nightscoutSecret = viewModel.nightscoutSecret,
                            followerPollSeconds = followerPollSeconds,
                            lluEmail = viewModel.lluEmail,
                            lluPassword = viewModel.lluPassword,
                            isNotificationAccessGranted = isNotifAccessGranted,
                            onGlucoseSourceChange = viewModel::setGlucoseSource,
                            onNightscoutUrlChange = viewModel::setNightscoutUrl,
                            onNightscoutSecretChange = viewModel::setNightscoutSecret,
                            onFollowerPollSecondsChange = viewModel::setFollowerPollSeconds,
                            onLluEmailChange = viewModel::setLluEmail,
                            onLluPasswordChange = viewModel::setLluPassword,
                            onOpenNotificationAccess = {
                                GlucoseNotificationListener.openSettings(this@MainActivity)
                            },
                            onPullFromNightscout = { days ->
                                pullWithToast(
                                    days,
                                    R.string.activity_pull_progress,
                                    R.string.activity_pull_success,
                                    R.string.activity_pull_failed,
                                    viewModel::pullFromNightscout
                                )
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings/treatments") {
                        TreatmentsSettings(
                            treatmentsSyncEnabled = treatmentsSyncEnabled,
                            insulinType = insulinType,
                            customDIA = customDIA,
                            nightscoutConfigured = nightscoutConfigured,
                            onTreatmentsSyncEnabledChange = viewModel::setTreatmentsSyncEnabled,
                            onInsulinTypeChange = viewModel::setInsulinType,
                            onCustomDIAChange = viewModel::setCustomDIA,
                            onPullTreatments = { days ->
                                pullWithToast(
                                    days,
                                    R.string.activity_pull_treatments_progress,
                                    R.string.activity_pull_treatments_success,
                                    R.string.activity_pull_treatments_failed,
                                    viewModel::pullTreatments
                                )
                            },
                            mealTimeSlotConfig = viewModel.mealTimeSlotConfig.collectAsState().value,
                            onMealSlotChange = { key, minutes ->
                                lifecycleScope.launch { viewModel.setMealSlotBoundary(key, minutes) }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings/exercise") {
                        com.psjostrom.strimma.ui.settings.ExerciseSettings(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings/display") {
                        DisplaySettings(
                            glucoseUnit = glucoseUnit,
                            hbA1cUnit = hbA1cUnit,
                            graphWindowHours = graphWindowHours,
                            bgLow = bgLow,
                            bgHigh = bgHigh,
                            themeMode = themeModeStr,
                            onGlucoseUnitChange = viewModel::setGlucoseUnit,
                            onHbA1cUnitChange = viewModel::setHbA1cUnit,
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
                            pauseLowExpiryMs = pauseLowExpiryMs,
                            pauseHighExpiryMs = pauseHighExpiryMs,
                            onPauseAlerts = viewModel::pauseAlerts,
                            onCancelPause = viewModel::cancelAlertPause,
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
                    composable("settings/general") {
                        val startOnBoot by viewModel.startOnBoot.collectAsState()
                        val language by viewModel.language.collectAsState()
                        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                        val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
                        val isBatteryOptimizationIgnored = remember(lifecycleState) {
                            getSystemService(PowerManager::class.java)
                                .isIgnoringBatteryOptimizations(packageName)
                        }
                        GeneralSettings(
                            startOnBoot = startOnBoot,
                            onStartOnBootChange = viewModel::setStartOnBoot,
                            language = language,
                            onLanguageChange = { tag ->
                                viewModel.setLanguage(tag)
                                val localeManager = getSystemService(android.app.LocaleManager::class.java)
                                localeManager.applicationLocales = if (tag.isEmpty()) {
                                    android.os.LocaleList.getEmptyLocaleList()
                                } else {
                                    android.os.LocaleList.forLanguageTags(tag)
                                }
                            },
                            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "",
                            isDebug = com.psjostrom.strimma.BuildConfig.DEBUG,
                            isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                            onOpenBatteryOptimization = {
                                // BatteryLife: Strimma is a CGM safety app whose core function
                                // (real-time glucose alerts) is adversely affected by Doze.
                                // Acceptable per https://developer.android.com/training/monitoring-device-state/doze-standby
                                @SuppressLint("BatteryLife")
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = "package:$packageName".toUri()
                                }
                                startActivity(intent)
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings/data") {
                        val webServerEnabled by viewModel.webServerEnabled.collectAsState()
                        DataSettings(
                            bgBroadcastEnabled = bgBroadcastEnabled,
                            onBgBroadcastEnabledChange = viewModel::setBgBroadcastEnabled,
                            webServerEnabled = webServerEnabled,
                            webServerSecret = viewModel.webServerSecret,
                            onWebServerEnabledChange = viewModel::setWebServerEnabled,
                            onWebServerSecretChange = viewModel::setWebServerSecret,
                            onExportReadings = {
                                lifecycleScope.launch {
                                    val csv = viewModel.exportCsv(EXPORT_HOURS_30_DAYS)
                                    shareCsv(this@MainActivity, csv, getString(R.string.activity_export_readings_chooser))
                                }
                            },
                            onExportSettings = {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle(getString(R.string.activity_export_dialog_title))
                                    .setMessage(getString(R.string.activity_export_dialog_message))
                                    .setPositiveButton(getString(R.string.common_export)) { _, _ ->
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
                                                    getString(R.string.activity_export_chooser)
                                                ))
                                            } catch (
                                                // File I/O + intent dispatch — multiple exception types
                                                @Suppress("TooGenericExceptionCaught")
                                                e: Exception
                                            ) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    getString(R.string.activity_export_failed, e.message ?: ""),
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                    .setNegativeButton(getString(R.string.common_cancel), null)
                                    .show()
                            },
                            onImportSettings = {
                                importSettingsLauncher.launch("*/*")
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("stats") {
                        StatsScreen(
                            bgLow = bgLow,
                            bgHigh = bgHigh,
                            glucoseUnit = glucoseUnit,
                            hbA1cUnit = hbA1cUnit,
                            onLoadReadings = viewModel::readingsForPeriod,
                            onLoadCarbTreatments = viewModel::carbTreatmentsInRange,
                            onLoadAllTreatments = viewModel::allTreatmentsSince,
                            treatmentsSyncEnabled = treatmentsSyncEnabled,
                            nightscoutConfigured = nightscoutConfigured,
                            tauMinutes = viewModel.currentTauMinutes(),
                            mealAnalyzer = viewModel.mealAnalyzer,
                            mealTimeSlotConfig = viewModel.mealTimeSlotConfig.collectAsState().value,
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
