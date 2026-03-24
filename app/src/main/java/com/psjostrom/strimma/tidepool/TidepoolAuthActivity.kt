package com.psjostrom.strimma.tidepool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.receiver.DebugLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the OIDC redirect callback from Tidepool login.
 * Receives the authorization code, exchanges it for tokens,
 * fetches user ID, then finishes.
 */
@AndroidEntryPoint
class TidepoolAuthActivity : ComponentActivity() {

    companion object {
        private const val LOG_ID_PREFIX_LENGTH = 8
    }

    @Inject lateinit var authManager: TidepoolAuthManager
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val redirectIntent = intent ?: run {
            DebugLog.log(message = "TidepoolAuthActivity: no intent")
            finish()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val success = authManager.handleAuthResponse(redirectIntent)
            if (success) {
                authManager.fetchUserId()?.let { userId ->
                    settings.setTidepoolUserId(userId)
                    DebugLog.log(message = "Tidepool auth complete, userId=${userId.take(LOG_ID_PREFIX_LENGTH)}...")
                }
            }
            finish()
        }
    }
}
