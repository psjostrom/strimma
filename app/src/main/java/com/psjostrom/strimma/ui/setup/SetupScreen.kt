package com.psjostrom.strimma.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import androidx.hilt.navigation.compose.hiltViewModel
import com.psjostrom.strimma.data.GlucoseSource
import com.psjostrom.strimma.ui.settings.AlertsViewModel
import com.psjostrom.strimma.ui.theme.InRange
import kotlinx.coroutines.launch

private const val STEP_COUNT = 5

@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    initialStep: Int,
    isNotificationAccessGranted: Boolean,
    isNotificationPermissionGranted: Boolean,
    isBatteryOptimizationIgnored: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onSetupComplete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialStep) { STEP_COUNT }
    val alertsViewModel: AlertsViewModel = hiltViewModel()

    val glucoseUnit by viewModel.glucoseUnit.collectAsState()
    val glucoseSource by viewModel.glucoseSource.collectAsState()

    // Alerts
    val alertUrgentLowEnabled by alertsViewModel.alertUrgentLowEnabled.collectAsState()
    val alertLowEnabled by alertsViewModel.alertLowEnabled.collectAsState()
    val alertHighEnabled by alertsViewModel.alertHighEnabled.collectAsState()
    val alertUrgentHighEnabled by alertsViewModel.alertUrgentHighEnabled.collectAsState()
    val alertStaleEnabled by alertsViewModel.alertStaleEnabled.collectAsState()
    val alertLowSoonEnabled by alertsViewModel.alertLowSoonEnabled.collectAsState()
    val alertHighSoonEnabled by alertsViewModel.alertHighSoonEnabled.collectAsState()
    val alertUrgentLow by alertsViewModel.alertUrgentLow.collectAsState()
    val alertLow by alertsViewModel.alertLow.collectAsState()
    val alertHigh by alertsViewModel.alertHigh.collectAsState()
    val alertUrgentHigh by alertsViewModel.alertUrgentHigh.collectAsState()

    val canAdvance = when (pagerState.currentPage) {
        2 -> when (glucoseSource) {
            GlucoseSource.COMPANION -> isNotificationAccessGranted
            GlucoseSource.NIGHTSCOUT_FOLLOWER -> true
            GlucoseSource.XDRIP_BROADCAST -> true
            GlucoseSource.LIBRELINKUP -> true
        }
        else -> true
    }

    val stepTitles = listOf(
        stringResource(R.string.setup_welcome_title),
        stringResource(R.string.setup_units_title),
        stringResource(R.string.setup_source_title),
        stringResource(R.string.setup_alerts_title),
        stringResource(R.string.setup_permissions_title)
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Step indicators
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(STEP_COUNT) { index ->
                    val isActive = index == pagerState.currentPage
                    val isDone = index < pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (isActive) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isActive -> InRange
                                    isDone -> InRange.copy(alpha = 0.4f)
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                    )
                }
            }

            // Step title
            Text(
                stepTitles[pagerState.currentPage],
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                userScrollEnabled = false
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Spacer(Modifier.height(8.dp))
                    when (page) {
                        0 -> SetupWelcomeStep()
                        1 -> SetupUnitsStep(
                            selectedUnit = glucoseUnit,
                            onUnitChange = { viewModel.setGlucoseUnit(it) }
                        )
                        2 -> SetupDataSourceStep(
                            selectedSource = glucoseSource,
                            onSourceChange = { viewModel.setGlucoseSource(it) },
                            isNotificationAccessGranted = isNotificationAccessGranted,
                            onOpenNotificationAccess = onOpenNotificationAccess,
                            onOpenAppInfo = onOpenAppInfo,
                            lluEmail = viewModel.lluEmail,
                            lluPassword = viewModel.lluPassword,
                            onLluEmailChange = { viewModel.setLluEmail(it) },
                            onLluPasswordChange = { viewModel.setLluPassword(it) }
                        )
                        3 -> SetupAlertsStep(
                            glucoseUnit = glucoseUnit,
                            alertUrgentLowEnabled = alertUrgentLowEnabled,
                            alertLowEnabled = alertLowEnabled,
                            alertHighEnabled = alertHighEnabled,
                            alertUrgentHighEnabled = alertUrgentHighEnabled,
                            alertStaleEnabled = alertStaleEnabled,
                            alertLowSoonEnabled = alertLowSoonEnabled,
                            alertHighSoonEnabled = alertHighSoonEnabled,
                            alertUrgentLow = alertUrgentLow,
                            alertLow = alertLow,
                            alertHigh = alertHigh,
                            alertUrgentHigh = alertUrgentHigh,
                            onAlertUrgentLowEnabledChange = alertsViewModel::setAlertUrgentLowEnabled,
                            onAlertLowEnabledChange = alertsViewModel::setAlertLowEnabled,
                            onAlertHighEnabledChange = alertsViewModel::setAlertHighEnabled,
                            onAlertUrgentHighEnabledChange = alertsViewModel::setAlertUrgentHighEnabled,
                            onAlertStaleEnabledChange = alertsViewModel::setAlertStaleEnabled,
                            onAlertLowSoonEnabledChange = alertsViewModel::setAlertLowSoonEnabled,
                            onAlertHighSoonEnabledChange = alertsViewModel::setAlertHighSoonEnabled,
                            onAlertUrgentLowChange = alertsViewModel::setAlertUrgentLow,
                            onAlertLowChange = alertsViewModel::setAlertLow,
                            onAlertHighChange = alertsViewModel::setAlertHigh,
                            onAlertUrgentHighChange = alertsViewModel::setAlertUrgentHigh
                        )
                        4 -> SetupPermissionsStep(
                            isNotificationPermissionGranted = isNotificationPermissionGranted,
                            isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                            isNotificationAccessGranted = isNotificationAccessGranted,
                            showNotificationAccess = glucoseSource == GlucoseSource.COMPANION,
                            onRequestNotificationPermission = onRequestNotificationPermission,
                            onRequestBatteryOptimization = onRequestBatteryOptimization,
                            onRequestNotificationAccess = onOpenNotificationAccess,
                            onStart = {
                                viewModel.setSetupCompleted()
                                onSetupComplete()
                            }
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            // Navigation buttons (not on last step — it has its own "Start Strimma" button)
            if (pagerState.currentPage < STEP_COUNT - 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (pagerState.currentPage > 0) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            }
                        ) {
                            Text(stringResource(R.string.setup_back))
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    Button(
                        onClick = {
                            val nextPage = pagerState.currentPage + 1
                            viewModel.setSetupStep(nextPage)
                            scope.launch {
                                pagerState.animateScrollToPage(nextPage)
                            }
                        },
                        enabled = canAdvance,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.setup_next))
                    }
                }
            }
        }
    }
}
