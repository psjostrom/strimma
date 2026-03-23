package com.psjostrom.strimma.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("strimma_sync", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("start_on_boot", true)) return

            val serviceIntent = Intent(context, StrimmaService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
