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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.IOBComputer
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.psjostrom.strimma.network.IntegrationStatus
import com.psjostrom.strimma.receiver.GlucoseNotificationListener
import com.psjostrom.strimma.update.DownloadState
import com.psjostrom.strimma.service.StrimmaService
import com.psjostrom.strimma.ui.settings.AlertsSettings
import com.psjostrom.strimma.ui.settings.AlertsViewModel
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

    private val tidepoolAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        lifecycleScope.launch {
            viewModelRef?.handleTidepoolAuthResult(data)
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
            val themeMode by viewModel.themeMode.collectAsState()

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
                val notifGraphMinutes by viewModel.notifGraphMinutes.collectAsState()
                val predictionMinutes by viewModel.predictionMinutes.collectAsState()
                val glucoseUnit by viewModel.glucoseUnit.collectAsState()
                val hbA1cUnit by viewModel.hbA1cUnit.collectAsState()
                val wallpaperShowGraph by viewModel.wallpaperShowGraph.collectAsState()
                val bgBroadcastEnabled by viewModel.bgBroadcastEnabled.collectAsState()
                val glucoseSource by viewModel.glucoseSource.collectAsState()
                val followerPollSeconds by viewModel.followerPollSeconds.collectAsState()
                val nightscoutConfigured by viewModel.nightscoutConfigured.collectAsState()
                val treatmentsSyncEnabled by viewModel.treatmentsSyncEnabled.collectAsState()
                val insulinType by viewModel.insulinType.collectAsState()
                val customDIA by viewModel.customDIA.collectAsState()
                val treatments by viewModel.treatments.collectAsState()
                val iob by viewModel.iob.collectAsState()
                val exerciseSessions by viewModel.exerciseSessions.collectAsState()
                val guidanceState by viewModel.guidanceState.collectAsState()

                // Auto-update dialog
                val updateInfo by viewModel.updateInfo.collectAsState()
                val updateDismissed by viewModel.updateDismissed.collectAsState()
                val downloadState by viewModel.downloadState.collectAsState()
                updateInfo?.let { info ->
                    if (!updateDismissed || info.isForced) {
                        UpdateDialog(
                            info = info,
                            downloadState = downloadState,
                            onUpdate = { viewModel.downloadUpdate(info) },
                            onDismiss = { viewModel.dismissUpdate() }
                        )
                    }
                }

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                val topLevelRoutes = setOf("main", "exercise", "stats", "settings")
                val showBottomBar = currentRoute in topLevelRoutes

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        if (showBottomBar) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                NavigationBarItem(
                                    selected = currentRoute == "main",
                                    onClick = {
                                        navController.navigate("main") {
                                            popUpTo("main") { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            if (currentRoute == "main") Icons.Filled.WaterDrop
                                            else Icons.Outlined.WaterDrop,
                                            contentDescription = null
                                        )
                                    },
                                    label = { Text(stringResource(R.string.nav_home)) }
                                )
                                NavigationBarItem(
                                    selected = currentRoute == "exercise",
                                    onClick = {
                                        navController.navigate("exercise") {
                                            popUpTo("main") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            if (currentRoute == "exercise") Icons.Filled.FitnessCenter
                                            else Icons.Outlined.FitnessCenter,
                                            contentDescription = null
                                        )
                                    },
                                    label = { Text(stringResource(R.string.nav_exercise)) }
                                )
                                NavigationBarItem(
                                    selected = currentRoute == "stats",
                                    onClick = {
                                        navController.navigate("stats") {
                                            popUpTo("main") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            if (currentRoute == "stats") Icons.Filled.BarChart
                                            else Icons.Outlined.BarChart,
                                            contentDescription = null
                                        )
                                    },
                                    label = { Text(stringResource(R.string.nav_stats)) }
                                )
                                NavigationBarItem(
                                    selected = currentRoute == "settings",
                                    onClick = {
                                        navController.navigate("settings") {
                                            popUpTo("main") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            if (currentRoute == "settings") Icons.Filled.Settings
                                            else Icons.Outlined.Settings,
                                            contentDescription = null
                                        )
                                    },
                                    label = { Text(stringResource(R.string.nav_settings)) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                NavHost(
                    navController,
                    startDestination = startDest,
                    modifier = Modifier
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding)
                ) {
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
                        val alertsViewModel: AlertsViewModel = hiltViewModel()
                        val pauseLowExpiryMs by alertsViewModel.pauseLowExpiryMs.collectAsState()
                        val pauseHighExpiryMs by alertsViewModel.pauseHighExpiryMs.collectAsState()
                        MainScreen(
                            latestReading = latestReading,
                            readings = readings,
                            bgLow = bgLow,
                            bgHigh = bgHigh,
                            graphWindowHours = graphWindowHours,
                            predictionMinutes = predictionMinutes,
                            glucoseUnit = glucoseUnit,
                            treatments = treatments,
                            iob = iob,
                            iobTauMinutes = IOBComputer.tauForInsulinType(insulinType, customDIA),
                            exerciseSessions = exerciseSessions,
                            guidanceState = guidanceState,
                            pauseLowExpiryMs = pauseLowExpiryMs,
                            pauseHighExpiryMs = pauseHighExpiryMs,
                            onPauseAlerts = alertsViewModel::pauseAlerts,
                            onCancelPause = alertsViewModel::cancelAlertPause,
                            onComputeBGContext = viewModel::computeExerciseBGContext
                        )
                    }
                    composable("exercise") {
                        ExerciseHistoryScreen(
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
                            nightscoutConfigured = nightscoutConfigured
                        )
                    }
                    composable("settings/data-source") {
                        val dsLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                        val dsLifecycleState by dsLifecycleOwner.lifecycle.currentStateFlow.collectAsState()
                        val isNotifAccessGranted = remember(dsLifecycleState) {
                            GlucoseNotificationListener.isEnabled(this@MainActivity)
                        }
                        val pushStatus by viewModel.pushStatus.collectAsState()
                        val nsFollowerStatus by viewModel.nsFollowerStatus.collectAsState()
                        val lluFollowerStatus by viewModel.lluFollowerStatus.collectAsState()
                        DataSourceSettings(
                            glucoseSource = glucoseSource,
                            nightscoutUrl = nightscoutUrl,
                            nightscoutSecret = viewModel.nightscoutSecret,
                            followerPollSeconds = followerPollSeconds,
                            lluEmail = viewModel.lluEmail,
                            lluPassword = viewModel.lluPassword,
                            pushStatus = pushStatus,
                            nsFollowerStatus = nsFollowerStatus,
                            lluFollowerStatus = lluFollowerStatus,
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
                        val treatmentSyncStatus by viewModel.treatmentSyncStatus.collectAsState()
                        TreatmentsSettings(
                            treatmentsSyncEnabled = treatmentsSyncEnabled,
                            insulinType = insulinType,
                            customDIA = customDIA,
                            nightscoutConfigured = nightscoutConfigured,
                            treatmentSyncStatus = treatmentSyncStatus,
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
                            themeMode = themeMode,
                            wallpaperShowGraph = wallpaperShowGraph,
                            onGlucoseUnitChange = viewModel::setGlucoseUnit,
                            onHbA1cUnitChange = viewModel::setHbA1cUnit,
                            onGraphWindowChange = viewModel::setGraphWindowHours,
                            onBgLowChange = viewModel::setBgLow,
                            onBgHighChange = viewModel::setBgHigh,
                            onThemeModeChange = viewModel::setThemeMode,
                            onWallpaperShowGraphChange = viewModel::setWallpaperShowGraph,
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
                        val alertsViewModel: AlertsViewModel = hiltViewModel()
                        val alertLowEnabled by alertsViewModel.alertLowEnabled.collectAsState()
                        val alertHighEnabled by alertsViewModel.alertHighEnabled.collectAsState()
                        val alertUrgentLowEnabled by alertsViewModel.alertUrgentLowEnabled.collectAsState()
                        val alertLow by alertsViewModel.alertLow.collectAsState()
                        val alertHigh by alertsViewModel.alertHigh.collectAsState()
                        val alertUrgentLow by alertsViewModel.alertUrgentLow.collectAsState()
                        val alertUrgentHighEnabled by alertsViewModel.alertUrgentHighEnabled.collectAsState()
                        val alertUrgentHigh by alertsViewModel.alertUrgentHigh.collectAsState()
                        val alertStaleEnabled by alertsViewModel.alertStaleEnabled.collectAsState()
                        val alertLowSoonEnabled by alertsViewModel.alertLowSoonEnabled.collectAsState()
                        val alertHighSoonEnabled by alertsViewModel.alertHighSoonEnabled.collectAsState()
                        val pauseLowExpiryMs by alertsViewModel.pauseLowExpiryMs.collectAsState()
                        val pauseHighExpiryMs by alertsViewModel.pauseHighExpiryMs.collectAsState()
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
                            onPauseAlerts = alertsViewModel::pauseAlerts,
                            onCancelPause = alertsViewModel::cancelAlertPause,
                            onAlertLowEnabledChange = alertsViewModel::setAlertLowEnabled,
                            onAlertHighEnabledChange = alertsViewModel::setAlertHighEnabled,
                            onAlertUrgentLowEnabledChange = alertsViewModel::setAlertUrgentLowEnabled,
                            onAlertUrgentHighEnabledChange = alertsViewModel::setAlertUrgentHighEnabled,
                            onAlertLowChange = alertsViewModel::setAlertLow,
                            onAlertHighChange = alertsViewModel::setAlertHigh,
                            onAlertUrgentLowChange = alertsViewModel::setAlertUrgentLow,
                            onAlertUrgentHighChange = alertsViewModel::setAlertUrgentHigh,
                            onAlertStaleEnabledChange = alertsViewModel::setAlertStaleEnabled,
                            onAlertLowSoonEnabledChange = alertsViewModel::setAlertLowSoonEnabled,
                            onAlertHighSoonEnabledChange = alertsViewModel::setAlertHighSoonEnabled,
                            onOpenAlertSound = alertsViewModel::openAlertChannelSettings,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings/general") {
                        val startOnBoot by viewModel.startOnBoot.collectAsState()
                        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                        val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
                        val isBatteryOptimizationIgnored = remember(lifecycleState) {
                            getSystemService(PowerManager::class.java)
                                .isIgnoringBatteryOptimizations(packageName)
                        }
                        GeneralSettings(
                            startOnBoot = startOnBoot,
                            onStartOnBootChange = viewModel::setStartOnBoot,
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
                        val tidepoolEnabled by viewModel.tidepoolEnabled.collectAsState()
                        val tidepoolLastUploadTime by viewModel.tidepoolLastUploadTime.collectAsState()
                        val tidepoolLastError by viewModel.tidepoolLastError.collectAsState()
                        DataSettings(
                            bgBroadcastEnabled = bgBroadcastEnabled,
                            onBgBroadcastEnabledChange = viewModel::setBgBroadcastEnabled,
                            webServerEnabled = webServerEnabled,
                            webServerSecret = viewModel.webServerSecret,
                            onWebServerEnabledChange = viewModel::setWebServerEnabled,
                            onWebServerSecretChange = viewModel::setWebServerSecret,
                            tidepoolEnabled = tidepoolEnabled,
                            onTidepoolEnabledChange = viewModel::setTidepoolEnabled,
                            isTidepoolLoggedIn = viewModel.tidepoolLoggedIn.collectAsState().value,
                            onTidepoolLogin = {
                                tidepoolAuthLauncher.launch(viewModel.buildTidepoolAuthIntent())
                            },
                            onTidepoolLogout = viewModel::tidepoolLogout,
                            tidepoolLastUploadTime = tidepoolLastUploadTime,
                            tidepoolLastError = tidepoolLastError,
                            onTidepoolForceUpload = viewModel::tidepoolForceUpload,
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
                            onExportCsv = viewModel::exportCsv
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
}
