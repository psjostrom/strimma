package com.psjostrom.strimma.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.service.StrimmaService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * Foreground-notification action target. Tapping "Start workout" / "End workout"
 * fires this receiver, which toggles the manager's persisted state and then makes
 * sure the service is alive so the notification text refreshes immediately
 * (without that nudge, a service that was killed by the OS would not redraw the
 * notification until the next CGM reading restarts it).
 */
@AndroidEntryPoint
class WorkoutModeReceiver : BroadcastReceiver() {

    @Inject lateinit var workoutModeManager: WorkoutModeManager

    /**
     * Application-scope CoroutineScope provided by AppModule (SupervisorJob +
     * Dispatchers.Default). Using this rather than `CoroutineScope(Dispatchers.IO)`
     * gives proper structured concurrency — exceptions are isolated to siblings
     * via the SupervisorJob, and the scope tracks the actual app lifetime.
     */
    @Inject lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        appScope.launch(Dispatchers.IO) {
            try {
                // Hard cap so a misbehaving DataStore can't sit on the receiver's
                // 10-second budget and ANR the broadcast — toggle is normally
                // single-digit-millisecond work, anything over 8s is pathological.
                withTimeout(BROADCAST_BUDGET_MS) {
                    workoutModeManager.toggle()
                }
                // Kick the service so the foreground notification rebuilds with the
                // new label/text immediately — without this, a service that was
                // recently killed by the OS won't redraw until the next CGM reading.
                startServiceForRefresh(context)
            } catch (e: CancellationException) {
                throw e
            } catch (
                // Catch every non-cancellation throw. DataStore can throw IOException, KeyStore
                // can throw various; crashing the receiver process is worse than logging.
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                DebugLog.log("workout toggle from notification failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun startServiceForRefresh(context: Context) {
        val intent = Intent(context, StrimmaService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    companion object {
        private const val BROADCAST_BUDGET_MS = 8_000L
    }
}
