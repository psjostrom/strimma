package com.psjostrom.strimma.update

import com.psjostrom.strimma.BuildConfig
import com.psjostrom.strimma.receiver.DebugLog
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList()
)

@Serializable
private data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String
)

@Serializable
private data class UpdateConfig(
    @SerialName("min_version") val minVersion: String? = null
)

@Singleton
class UpdateChecker @Inject constructor() {

    companion object {
        private const val GITHUB_REPO = "psjostrom/strimma"
        private const val RELEASES_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
        private const val UPDATE_JSON_URL =
            "https://raw.githubusercontent.com/$GITHUB_REPO/main/update.json"
        private const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000 // 12 hours
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val MAX_LOG_MESSAGE_LENGTH = 80
    }

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo

    private val _dismissed = MutableStateFlow(false)
    val dismissed: StateFlow<Boolean> = _dismissed

    private var checkJob: Job? = null

    internal var client: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    fun start(scope: CoroutineScope) {
        if (checkJob != null) return
        checkJob = scope.launch {
            check()
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                check()
            }
        }
    }

    fun stop() {
        checkJob?.cancel()
        checkJob = null
    }

    fun dismiss() {
        _dismissed.value = true
    }

    @Suppress("TooGenericExceptionCaught") // Network boundary
    suspend fun check() {
        try {
            val release = fetchLatestRelease() ?: return
            val currentVersion = BuildConfig.VERSION_NAME

            val releaseVersion = release.tagName.removePrefix("v")
            if (!VersionComparator.isOlderThan(currentVersion, releaseVersion)) {
                _updateInfo.value = null
                return
            }

            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
            if (apkAsset == null) {
                DebugLog.log("Update $releaseVersion found but no APK asset")
                return
            }

            val minVersion = fetchMinVersion()
            val isForced = minVersion != null &&
                VersionComparator.isOlderThan(currentVersion, minVersion)

            _updateInfo.value = UpdateInfo(
                version = releaseVersion,
                changelog = release.body?.trim() ?: "",
                apkUrl = apkAsset.downloadUrl,
                isForced = isForced
            )
            DebugLog.log("Update available: $releaseVersion (forced=$isForced)")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.log("Update check failed: ${e.message?.take(MAX_LOG_MESSAGE_LENGTH)}")
        }
    }

    private suspend fun fetchLatestRelease(): GitHubRelease? {
        val response = client.get(RELEASES_URL) {
            header("Accept", "application/vnd.github+json")
        }
        if (!response.status.isSuccess()) {
            DebugLog.log("GitHub releases HTTP ${response.status.value}")
            return null
        }
        return response.body()
    }

    private suspend fun fetchMinVersion(): String? {
        return try {
            val response = client.get(UPDATE_JSON_URL)
            if (!response.status.isSuccess()) return null
            val config = Json.decodeFromString<UpdateConfig>(response.body<String>())
            config.minVersion
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            DebugLog.log("Failed to fetch update.json: ${e.message?.take(MAX_LOG_MESSAGE_LENGTH)}")
            null
        }
    }
}
