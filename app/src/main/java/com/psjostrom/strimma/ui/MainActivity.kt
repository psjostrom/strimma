package com.psjostrom.strimma.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.psjostrom.strimma.service.StrimmaService
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Service starts regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission (API 33+)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Start foreground service
        startForegroundService(Intent(this, StrimmaService::class.java))

        setContent {
            StrimmaTheme {
                val viewModel: MainViewModel = hiltViewModel()
                val navController = rememberNavController()

                val latestReading by viewModel.latestReading.collectAsState()
                val readings by viewModel.readings.collectAsState()
                val bgLow by viewModel.bgLow.collectAsState()
                val bgHigh by viewModel.bgHigh.collectAsState()
                val graphWindowHours by viewModel.graphWindowHours.collectAsState()
                val springaUrl by viewModel.springaUrl.collectAsState()

                NavHost(navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            latestReading = latestReading,
                            readings = readings,
                            bgLow = bgLow,
                            bgHigh = bgHigh,
                            graphWindowHours = graphWindowHours,
                            onSettingsClick = { navController.navigate("settings") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            springaUrl = springaUrl,
                            apiSecret = viewModel.apiSecret,
                            graphWindowHours = graphWindowHours,
                            bgLow = bgLow,
                            bgHigh = bgHigh,
                            onSpringaUrlChange = viewModel::setSpringaUrl,
                            onApiSecretChange = viewModel::setApiSecret,
                            onGraphWindowChange = viewModel::setGraphWindowHours,
                            onBgLowChange = viewModel::setBgLow,
                            onBgHighChange = viewModel::setBgHigh,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
