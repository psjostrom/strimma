package com.psjostrom.strimma.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import com.psjostrom.strimma.receiver.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class DownloadState { IDLE, DOWNLOADING, READY, FAILED }

@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val _state = MutableStateFlow(DownloadState.IDLE)
    val state: StateFlow<DownloadState> = _state

    private var downloadId: Long = -1L
    private var receiver: BroadcastReceiver? = null

    fun download(apkUrl: String, version: String) {
        if (_state.value == DownloadState.DOWNLOADING) return

        unregisterReceiver()
        cleanOldApks()
        _state.value = DownloadState.DOWNLOADING
        val fileName = "strimma-$version.apk"

        val request = DownloadManager.Request(apkUrl.toUri())
            .setTitle("Strimma $version")
            .setDescription("Downloading update...")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        val dm = context.getSystemService(DownloadManager::class.java)
        downloadId = dm.enqueue(request)

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return

                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                try {
                    if (cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                        if (cursor.getInt(statusIdx) == DownloadManager.STATUS_SUCCESSFUL) {
                            _state.value = DownloadState.READY
                            DebugLog.log("Update APK downloaded: $fileName")
                            installApk(version)
                        } else {
                            _state.value = DownloadState.FAILED
                            DebugLog.log("Update download failed")
                        }
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught")
                    e: Exception
                ) {
                    _state.value = DownloadState.FAILED
                    DebugLog.log("Download query failed: ${e.message}")
                } finally {
                    cursor.close()
                    unregisterReceiver()
                }
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    @Suppress("TooGenericExceptionCaught") // System boundary (FileProvider, PackageInstaller)
    private fun installApk(version: String) {
        val fileName = "strimma-$version.apk"
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (dir == null) {
            DebugLog.log("External files dir unavailable for install")
            _state.value = DownloadState.FAILED
            return
        }
        val file = java.io.File(dir, fileName)
        if (!file.exists()) {
            DebugLog.log("APK file not found: $fileName")
            _state.value = DownloadState.FAILED
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            DebugLog.log("Install failed: ${e.message}")
            _state.value = DownloadState.FAILED
        }
    }

    private fun cleanOldApks() {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        dir.listFiles()?.filter { it.name.startsWith("strimma-") && it.name.endsWith(".apk") }
            ?.forEach { it.delete() }
    }

    private fun unregisterReceiver() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) { /* Already unregistered */ }
            receiver = null
        }
    }
}
