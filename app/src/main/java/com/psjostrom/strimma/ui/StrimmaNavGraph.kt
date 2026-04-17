package com.psjostrom.strimma.ui

import android.content.Intent
import android.os.PowerManager
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.psjostrom.strimma.R
import com.psjostrom.strimma.receiver.GlucoseNotificationListener
import com.psjostrom.strimma.ui.settings.AlertsSettings
import com.psjostrom.strimma.ui.settings.AlertsViewModel
import com.psjostrom.strimma.ui.settings.DataSettings
import com.psjostrom.strimma.ui.settings.DataSourceSettings
import com.psjostrom.strimma.ui.settings.DisplaySettings
import com.psjostrom.strimma.ui.settings.GeneralSettings
import com.psjostrom.strimma.ui.settings.NotificationSettings
import com.psjostrom.strimma.ui.settings.TreatmentsSettings
import com.psjostrom.strimma.ui.setup.SetupScreen
import com.psjostrom.strimma.ui.setup.SetupViewModel
import com.psjostrom.strimma.ui.setup.defaultUnitForLocale
import kotlinx.coroutines.launch

private val TOP_LEVEL_ROUTES = setOf("main", "exercise", "stats", "settings")

@Composable
fun StrimmaBottomBar(
    currentRoute: String?,
    navController: NavHostController,
) {
    if (currentRoute !in TOP_LEVEL_ROUTES) return

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

@Suppress("LongMethod", "CyclomaticComplexMethod") // Nav graph wiring — one composable per route
@Composable
fun StrimmaNavGraph(
    navController: NavHostController,
    innerPadding: PaddingValues,
    viewModel: MainViewModel,
    activity: MainActivity,
) {
    val scope = rememberCoroutineScope()
    val bgLow by viewModel.bgLow.collectAsState()
    val bgHigh by viewModel.bgHigh.collectAsState()
    val graphWindowHours by viewModel.graphWindowHours.collectAsState()
    val predictionMinutes by viewModel.predictionMinutes.collectAsState()
    val glucoseUnit by viewModel.glucoseUnit.collectAsState()
    val nightscoutConfigured by viewModel.nightscoutConfigured.collectAsState()
    val latestReading by viewModel.latestReading.collectAsState()
    val readings by viewModel.readings.collectAsState()
    val treatments by viewModel.treatments.collectAsState()
    val iob by viewModel.iob.collectAsState()
    val exerciseSessions by viewModel.exerciseSessions.collectAsState()
    val guidanceState by viewModel.guidanceState.collectAsState()

    val startDest = if (viewModel.setupCompleted.collectAsState().value == true) "main" else "setup"

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
                GlucoseNotificationListener.isEnabled(activity)
            }
            val isNotifPermGranted = remember(lifecycleState) {
                ContextCompat.checkSelfPermission(
                    activity, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            val isBatteryIgnored = remember(lifecycleState) {
                activity.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(activity.packageName)
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
                    activity.requestNotificationPermission()
                },
                onRequestBatteryOptimization = {
                    activity.requestBatteryOptimization()
                },
                onOpenNotificationAccess = {
                    GlucoseNotificationListener.openSettings(activity)
                },
                onOpenAppInfo = {
                    activity.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = "package:${activity.packageName}".toUri()
                        }
                    )
                },
                onSetupComplete = {
                    activity.startServiceIfSetupDone()
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
            val storyReady by viewModel.storyReady.collectAsState()
            val lastMonth = java.time.YearMonth.now().minusMonths(1)
            val storyMonthName = lastMonth.month.getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.getDefault()
            )
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
                iobTauMinutes = viewModel.tauMinutes.collectAsState().value,
                exerciseSessions = exerciseSessions,
                guidanceState = guidanceState,
                pauseLowExpiryMs = pauseLowExpiryMs,
                pauseHighExpiryMs = pauseHighExpiryMs,
                onPauseAlerts = alertsViewModel::pauseAlerts,
                onCancelPause = alertsViewModel::cancelAlertPause,
                onComputeBGContext = viewModel::computeExerciseBGContext,
                storyReady = storyReady,
                storyMonthName = storyMonthName,
                onNavigateToStory = {
                    val lm = java.time.YearMonth.now().minusMonths(1)
                    navController.navigate("story/${lm.year}/${lm.monthValue}") {
                        launchSingleTop = true
                    }
                }
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
                GlucoseNotificationListener.isEnabled(activity)
            }
            val glucoseSource by viewModel.settings.glucoseSource.collectAsState(
                initial = com.psjostrom.strimma.data.GlucoseSource.COMPANION
            )
            val nightscoutUrl by viewModel.settings.nightscoutUrl.collectAsState(initial = "")
            val followerPollSeconds by viewModel.settings.followerPollSeconds.collectAsState(initial = 60)
            val pushStatus by viewModel.pushStatus.collectAsState()
            val nsFollowerStatus by viewModel.nsFollowerStatus.collectAsState()
            val lluFollowerStatus by viewModel.lluFollowerStatus.collectAsState()
            DataSourceSettings(
                glucoseSource = glucoseSource,
                nightscoutUrl = nightscoutUrl,
                nightscoutSecret = viewModel.settings.getNightscoutSecret(),
                followerPollSeconds = followerPollSeconds,
                lluEmail = viewModel.settings.getLluEmail(),
                lluPassword = viewModel.settings.getLluPassword(),
                pushStatus = pushStatus,
                nsFollowerStatus = nsFollowerStatus,
                lluFollowerStatus = lluFollowerStatus,
                isNotificationAccessGranted = isNotifAccessGranted,
                onGlucoseSourceChange = { scope.launch { viewModel.settings.setGlucoseSource(it) } },
                onNightscoutUrlChange = { scope.launch { viewModel.settings.setNightscoutUrl(it) } },
                onNightscoutSecretChange = { viewModel.settings.setNightscoutSecret(it) },
                onFollowerPollSecondsChange = { scope.launch { viewModel.settings.setFollowerPollSeconds(it) } },
                onLluEmailChange = { viewModel.settings.setLluEmail(it) },
                onLluPasswordChange = { viewModel.settings.setLluPassword(it) },
                onOpenNotificationAccess = {
                    GlucoseNotificationListener.openSettings(activity)
                },
                onPullFromNightscout = { days ->
                    activity.pullWithToast(
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
            val treatmentsSyncEnabled by viewModel.settings.treatmentsSyncEnabled.collectAsState(initial = false)
            val insulinType by viewModel.settings.insulinType.collectAsState(
                initial = com.psjostrom.strimma.data.InsulinType.FIASP
            )
            val customDIA by viewModel.settings.customDIA.collectAsState(initial = 5.0f)
            val treatmentSyncStatus by viewModel.treatmentSyncStatus.collectAsState()
            TreatmentsSettings(
                treatmentsSyncEnabled = treatmentsSyncEnabled,
                insulinType = insulinType,
                customDIA = customDIA,
                nightscoutConfigured = nightscoutConfigured,
                treatmentSyncStatus = treatmentSyncStatus,
                onTreatmentsSyncEnabledChange = { scope.launch { viewModel.settings.setTreatmentsSyncEnabled(it) } },
                onInsulinTypeChange = { scope.launch { viewModel.settings.setInsulinType(it) } },
                onCustomDIAChange = { scope.launch { viewModel.settings.setCustomDIA(it) } },
                onPullTreatments = { days ->
                    activity.pullWithToast(
                        days,
                        R.string.activity_pull_treatments_progress,
                        R.string.activity_pull_treatments_success,
                        R.string.activity_pull_treatments_failed,
                        viewModel::pullTreatments
                    )
                },
                mealTimeSlotConfig = viewModel.mealTimeSlotConfig.collectAsState().value,
                onMealSlotChange = { key, minutes ->
                    activity.launchInScope { viewModel.settings.setMealSlotBoundary(key, minutes) }
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
            val hbA1cUnit by viewModel.settings.hbA1cUnit.collectAsState(
                initial = com.psjostrom.strimma.data.HbA1cUnit.MMOL_MOL
            )
            val themeMode by viewModel.settings.themeMode.collectAsState(
                initial = com.psjostrom.strimma.ui.theme.ThemeMode.System
            )
            DisplaySettings(
                glucoseUnit = glucoseUnit,
                hbA1cUnit = hbA1cUnit,
                graphWindowHours = graphWindowHours,
                bgLow = bgLow,
                bgHigh = bgHigh,
                themeMode = themeMode,
                onGlucoseUnitChange = { scope.launch { viewModel.settings.setGlucoseUnit(it) } },
                onHbA1cUnitChange = { scope.launch { viewModel.settings.setHbA1cUnit(it) } },
                onGraphWindowChange = { scope.launch { viewModel.settings.setGraphWindowHours(it) } },
                onBgLowChange = { scope.launch { viewModel.settings.setBgLow(it) } },
                onBgHighChange = { scope.launch { viewModel.settings.setBgHigh(it) } },
                onThemeModeChange = { scope.launch { viewModel.settings.setThemeMode(it) } },
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings/notifications") {
            val notifGraphMinutes by viewModel.settings.notifGraphMinutes.collectAsState(initial = 60)
            NotificationSettings(
                notifGraphMinutes = notifGraphMinutes,
                predictionMinutes = predictionMinutes,
                onNotifGraphMinutesChange = { scope.launch { viewModel.settings.setNotifGraphMinutes(it) } },
                onPredictionMinutesChange = { scope.launch { viewModel.settings.setPredictionMinutes(it) } },
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
            val startOnBoot by viewModel.settings.startOnBoot.collectAsState(initial = true)
            val updateCheckState by viewModel.updateCheckState.collectAsState()
            val betaCheckState by viewModel.betaCheckState.collectAsState()
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
            val isBatteryOptimizationIgnored = remember(lifecycleState) {
                activity.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(activity.packageName)
            }
            GeneralSettings(
                startOnBoot = startOnBoot,
                onStartOnBootChange = { scope.launch { viewModel.settings.setStartOnBoot(it) } },
                appVersion = activity.packageManager.getPackageInfo(
                    activity.packageName, 0
                ).versionName ?: "",
                isDebug = com.psjostrom.strimma.BuildConfig.DEBUG,
                updateCheckState = updateCheckState,
                onCheckForUpdates = viewModel::checkForUpdates,
                betaCheckState = betaCheckState,
                onCheckForBeta = viewModel::checkForBeta,
                isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                onOpenBatteryOptimization = {
                    activity.requestBatteryOptimization()
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings/data") {
            val bgBroadcastEnabled by viewModel.settings.bgBroadcastEnabled.collectAsState(initial = false)
            val webServerEnabled by viewModel.settings.webServerEnabled.collectAsState(initial = false)
            val tidepoolEnabled by viewModel.settings.tidepoolEnabled.collectAsState(initial = false)
            val tidepoolLastUploadTime by viewModel.settings.tidepoolLastUploadTime.collectAsState(initial = 0L)
            val tidepoolLastError by viewModel.settings.tidepoolLastError.collectAsState(initial = "")
            DataSettings(
                bgBroadcastEnabled = bgBroadcastEnabled,
                onBgBroadcastEnabledChange = { scope.launch { viewModel.settings.setBgBroadcastEnabled(it) } },
                webServerEnabled = webServerEnabled,
                webServerSecret = viewModel.settings.getWebServerSecret(),
                onWebServerEnabledChange = { scope.launch { viewModel.settings.setWebServerEnabled(it) } },
                onWebServerSecretChange = { viewModel.settings.setWebServerSecret(it) },
                tidepoolEnabled = tidepoolEnabled,
                onTidepoolEnabledChange = { scope.launch { viewModel.settings.setTidepoolEnabled(it) } },
                isTidepoolLoggedIn = viewModel.tidepoolLoggedIn.collectAsState().value,
                onTidepoolLogin = {
                    activity.launchTidepoolAuth(viewModel)
                },
                onTidepoolLogout = viewModel::tidepoolLogout,
                tidepoolLastUploadTime = tidepoolLastUploadTime,
                tidepoolLastError = tidepoolLastError,
                onTidepoolForceUpload = {
                    activity.handleDataAction(DataAction.TIDEPOOL_FORCE_UPLOAD, viewModel)
                },
                onExportReadings = {
                    activity.handleDataAction(DataAction.EXPORT_READINGS, viewModel)
                },
                onExportSettings = {
                    activity.handleDataAction(DataAction.EXPORT_SETTINGS, viewModel)
                },
                onImportSettings = {
                    activity.handleDataAction(DataAction.IMPORT_SETTINGS, viewModel)
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable("stats") {
            val treatmentsSyncEnabled by viewModel.settings.treatmentsSyncEnabled.collectAsState(initial = false)
            val hbA1cUnit by viewModel.settings.hbA1cUnit.collectAsState(
                initial = com.psjostrom.strimma.data.HbA1cUnit.MMOL_MOL
            )
            val tauMinutes by viewModel.tauMinutes.collectAsState()
            val storyViewedMonth by viewModel.settings.storyViewedMonth.collectAsState(initial = "")
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
                tauMinutes = tauMinutes,
                mealAnalyzer = viewModel.mealAnalyzer,
                mealTimeSlotConfig = viewModel.mealTimeSlotConfig.collectAsState().value,
                onExportCsv = viewModel::exportCsv,
                onNavigateToStory = { year, month ->
                    navController.navigate("story/$year/$month") {
                        launchSingleTop = true
                    }
                },
                storyViewedMonth = storyViewedMonth
            )
        }
        composable(
            "story/{year}/{month}",
            arguments = listOf(
                androidx.navigation.navArgument("year") {
                    type = androidx.navigation.NavType.IntType
                },
                androidx.navigation.navArgument("month") {
                    type = androidx.navigation.NavType.IntType
                }
            )
        ) {
            val hbA1cUnit by viewModel.settings.hbA1cUnit.collectAsState(
                initial = com.psjostrom.strimma.data.HbA1cUnit.MMOL_MOL
            )
            com.psjostrom.strimma.ui.story.StoryScreen(
                glucoseUnit = glucoseUnit,
                hbA1cUnit = hbA1cUnit,
                onBack = { navController.popBackStack() }
            )
        }
        composable("debug") {
            DebugScreen(onBack = { navController.popBackStack() })
        }
    }
}
