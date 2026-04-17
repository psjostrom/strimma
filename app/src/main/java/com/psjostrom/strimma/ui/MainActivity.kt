package com.psjostrom.strimma.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.psjostrom.strimma.R
import com.psjostrom.strimma.service.StrimmaService
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File

private const val EXPORT_HOURS_30_DAYS = 720

internal enum class DataAction {
    TIDEPOOL_FORCE_UPLOAD, EXPORT_READINGS, EXPORT_SETTINGS, IMPORT_SETTINGS
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var permissionsChecked = false
    private var viewModelRef: MainViewModel? = null

    internal fun pullWithToast(
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

    internal fun launchInScope(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }

    internal fun requestNotificationPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    @SuppressLint("BatteryLife") // CGM safety app — acceptable per Android Doze docs
    internal fun requestBatteryOptimization() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
        )
    }

    internal fun launchTidepoolAuth(viewModel: MainViewModel) {
        tidepoolAuthLauncher.launch(viewModel.buildTidepoolAuthIntent())
    }

    internal fun handleDataAction(action: DataAction, viewModel: MainViewModel) {
        when (action) {
            DataAction.TIDEPOOL_FORCE_UPLOAD -> lifecycleScope.launch {
                Toast.makeText(this@MainActivity, getString(R.string.activity_tidepool_uploading), Toast.LENGTH_SHORT).show()
                try {
                    val count = viewModel.tidepoolForceUpload()
                    val msg = if (count > 0) getString(R.string.activity_tidepool_success, count)
                        else getString(R.string.activity_tidepool_up_to_date)
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                } catch (
                    @Suppress("TooGenericExceptionCaught")
                    e: Exception
                ) {
                    val msg = getString(R.string.activity_tidepool_failed, e.message ?: "")
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
            DataAction.EXPORT_READINGS -> lifecycleScope.launch {
                val csv = viewModel.exportCsv(EXPORT_HOURS_30_DAYS)
                shareCsv(this@MainActivity, csv, getString(R.string.activity_export_readings_chooser))
            }
            DataAction.EXPORT_SETTINGS -> showExportSettingsDialog(viewModel)
            DataAction.IMPORT_SETTINGS -> importSettingsLauncher.launch("*/*")
        }
    }

    private fun showExportSettingsDialog(viewModel: MainViewModel) {
        AlertDialog.Builder(this)
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
    internal fun startServiceIfSetupDone() {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            viewModelRef = viewModel
            val setupCompleted by viewModel.setupCompleted.collectAsState()
            val themeMode by viewModel.settings.themeMode.collectAsState(
                initial = com.psjostrom.strimma.ui.theme.ThemeMode.System
            )

            // Wait for DataStore to load before showing anything
            if (setupCompleted == null) return@setContent

            // Start service once we know setup is done
            LaunchedEffect(setupCompleted) {
                if (setupCompleted == true) startServiceIfSetupDone()
            }

            StrimmaTheme(themeMode = themeMode) {
                val navController = rememberNavController()

                // Auto-update dialog (stable only)
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

                // Beta update dialog (manual check only)
                val betaUpdateInfo by viewModel.betaUpdateInfo.collectAsState()
                betaUpdateInfo?.let { info ->
                    UpdateDialog(
                        info = info,
                        downloadState = downloadState,
                        onUpdate = { viewModel.downloadUpdate(info) },
                        onDismiss = { viewModel.clearBetaUpdate() }
                    )
                }

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        StrimmaBottomBar(
                            currentRoute = currentRoute,
                            navController = navController,
                        )
                    }
                ) { innerPadding ->
                    StrimmaNavGraph(
                        navController = navController,
                        innerPadding = innerPadding,
                        viewModel = viewModel,
                        activity = this@MainActivity,
                    )
                }
            }
        }
    }
}
