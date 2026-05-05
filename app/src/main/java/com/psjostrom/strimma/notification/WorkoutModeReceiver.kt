package com.psjostrom.strimma.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class WorkoutModeReceiver : BroadcastReceiver() {

    @Inject lateinit var workoutModeManager: WorkoutModeManager

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                workoutModeManager.toggle()
            } finally {
                pending.finish()
            }
        }
    }
}
