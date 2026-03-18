package com.psjostrom.strimma

import android.app.Application
import com.psjostrom.strimma.receiver.DebugLog
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StrimmaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(filesDir)
    }
}
