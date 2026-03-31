package com.psjostrom.strimma.tidepool

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.receiver.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Manages Tidepool OIDC authentication via AppAuth-Android.
 * Handles login (PKCE auth code flow), token refresh, userinfo fetch, and logout.
 */
@Singleton
class TidepoolAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository
) {

    companion object {
        private const val CLIENT_ID = "strimma"
        private const val REDIRECT_URI = "strimma://callback/tidepool"
        private const val SCOPES = "openid email data_read data_write offline_access"
        private const val TOKEN_EXPIRY_BUFFER_MS = 30_000L
        private const val MAX_ERROR_LENGTH = 80
        private const val LOG_ID_PREFIX_LENGTH = 8
    }

    private val authService = AuthorizationService(context)

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private var accessToken: String? = null
    private var accessTokenExpiry: Long = 0L
    private val refreshMutex = Mutex()

    /**
     * Builds an Intent that launches the OIDC authorization flow in a Custom Tab.
     * The caller should startActivity with this intent.
     */
    fun buildAuthIntent(environment: String): Intent {
        val authBase = authBaseUrl(environment)
        val config = AuthorizationServiceConfiguration(
            "$authBase/protocol/openid-connect/auth".toUri(),
            "$authBase/protocol/openid-connect/token".toUri()
        )

        val request = AuthorizationRequest.Builder(
            config,
            CLIENT_ID,
            ResponseTypeValues.CODE,
            REDIRECT_URI.toUri()
        )
            .setScopes(SCOPES.split(" "))
            .build()

        return authService.getAuthorizationRequestIntent(request)
    }

    /**
     * Handles the redirect intent after OIDC login.
     * Exchanges the authorization code for tokens.
     * Returns true if tokens were obtained successfully.
     */
    suspend fun handleAuthResponse(intent: Intent): Boolean {
        val response = AuthorizationResponse.fromIntent(intent)
        if (response == null) {
            val errorMsg = net.openid.appauth.AuthorizationException.fromIntent(intent)
                ?.message?.take(MAX_ERROR_LENGTH) ?: "no response"
            DebugLog.log(message = "Tidepool auth failed: $errorMsg")
            return false
        }

        val tokenRequest = response.createTokenExchangeRequest()
        return performTokenExchange(tokenRequest) != null
    }

    /**
     * Returns a valid access token, refreshing if necessary.
     * Returns null if not logged in or refresh fails.
     */
    suspend fun getValidAccessToken(): String? {
        val current = accessToken
        if (current != null && System.currentTimeMillis() < accessTokenExpiry - TOKEN_EXPIRY_BUFFER_MS) {
            return current
        }

        return refreshAccessToken()
    }

    /**
     * Fetches the user ID from the OIDC userinfo endpoint.
     * Returns the "sub" claim (Tidepool user ID) or null on failure.
     */
    suspend fun fetchUserId(): String? {
        val token = getValidAccessToken() ?: return null
        val environment = settings.tidepoolEnvironment.first()
        val authBase = authBaseUrl(environment)
        val url = "$authBase/protocol/openid-connect/userinfo"

        return try {
            val response = httpClient.get(url) {
                header("Authorization", "Bearer $token")
            }

            if (!response.status.isSuccess()) {
                DebugLog.log(message = "Tidepool userinfo HTTP ${response.status.value}")
                return null
            }

            val userInfo = response.body<UserInfoResponse>()
            DebugLog.log(message = "Tidepool userinfo fetched, sub=${userInfo.sub.take(LOG_ID_PREFIX_LENGTH)}...")
            userInfo.sub
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") // Network boundary
            e: Exception
        ) {
            DebugLog.log(message = "Tidepool userinfo error: ${e.message?.take(MAX_ERROR_LENGTH)}")
            null
        }
    }

    /**
     * Returns true if a refresh token is stored (user has logged in before).
     */
    fun isLoggedIn(): Boolean = settings.getTidepoolRefreshToken().isNotBlank()

    /**
     * Clears all stored tokens and user-specific state.
     */
    suspend fun logout() {
        accessToken = null
        accessTokenExpiry = 0L
        settings.setTidepoolRefreshToken("")
        settings.setTidepoolUserId("")
        settings.setTidepoolDatasetId("")
        settings.setTidepoolLastError("")
        DebugLog.log(message = "Tidepool logged out")
    }

    private suspend fun refreshAccessToken(): String? = refreshMutex.withLock {
        // Double-check after acquiring lock — another coroutine may have refreshed
        val current = accessToken
        if (current != null && System.currentTimeMillis() < accessTokenExpiry - TOKEN_EXPIRY_BUFFER_MS) {
            return@withLock current
        }

        val refreshToken = settings.getTidepoolRefreshToken()
        if (refreshToken.isBlank()) return@withLock null

        val environment = settings.tidepoolEnvironment.first()
        val authBase = authBaseUrl(environment)
        val config = AuthorizationServiceConfiguration(
            "$authBase/protocol/openid-connect/auth".toUri(),
            "$authBase/protocol/openid-connect/token".toUri()
        )

        val tokenRequest = TokenRequest.Builder(config, CLIENT_ID)
            .setGrantType("refresh_token")
            .setRefreshToken(refreshToken)
            .build()

        return@withLock performTokenExchange(tokenRequest, isRefresh = true)
    }

    private suspend fun performTokenExchange(
        tokenRequest: TokenRequest,
        isRefresh: Boolean = false
    ): String? = suspendCoroutine { continuation ->
        authService.performTokenRequest(tokenRequest) { response, exception ->
            if (response != null && response.accessToken != null) {
                accessToken = response.accessToken
                accessTokenExpiry = response.accessTokenExpirationTime ?: 0L
                response.refreshToken?.let { settings.setTidepoolRefreshToken(it) }
                val action = if (isRefresh) "refresh" else "exchange"
                DebugLog.log(message = "Tidepool token $action success")
                continuation.resume(response.accessToken)
            } else {
                if (isRefresh) {
                    val errorType = exception?.type?.toString() ?: ""
                    if (errorType.contains("invalid_grant")) {
                        DebugLog.log(message = "Tidepool refresh token expired, clearing")
                        settings.setTidepoolRefreshToken("")
                        accessToken = null
                        accessTokenExpiry = 0L
                    } else {
                        DebugLog.log(
                            message = "Tidepool token refresh failed: ${exception?.message?.take(MAX_ERROR_LENGTH)}"
                        )
                    }
                } else {
                    DebugLog.log(
                        message = "Tidepool token exchange failed: ${exception?.message?.take(MAX_ERROR_LENGTH)}"
                    )
                }
                continuation.resume(null)
            }
        }
    }

    /**
     * Returns the data API base URL for the given environment.
     */
    fun getApiBase(environment: String): String = when (environment.uppercase()) {
        "INTEGRATION" -> "https://api.integration.tidepool.org"
        else -> "https://api.tidepool.org"
    }

    private fun authBaseUrl(environment: String): String = when (environment.uppercase()) {
        "INTEGRATION" -> "https://auth.integration.tidepool.org/realms/integration"
        else -> "https://auth.tidepool.org/realms/tidepool"
    }
}

@Serializable
private data class UserInfoResponse(val sub: String)
