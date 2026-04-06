package com.psjostrom.strimma.wallpaper

import android.graphics.Color
import android.graphics.PorterDuff
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.ReadingDao
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

class BgWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = BgWallpaperEngine()

    inner class BgWallpaperEngine : Engine() {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val renderer = BgWallpaperRenderer()
        private var collectJob: Job? = null
        private var surfaceWidth = 0
        private var surfaceHeight = 0

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                startCollecting()
            } else {
                stopCollecting()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            if (isVisible) {
                stopCollecting()
                startCollecting()
            }
        }

        override fun onDestroy() {
            stopCollecting()
            scope.cancel()
            super.onDestroy()
        }

        private fun startCollecting() {
            if (collectJob?.isActive == true) return

            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext, WallpaperEntryPoint::class.java
            )
            val readingDao = entryPoint.readingDao()
            val settings = entryPoint.settingsRepository()

            collectJob = scope.launch {
                combine(
                    readingDao.latest(),
                    recentReadingsFlow(readingDao),
                    settings.glucoseUnit,
                    settings.bgLow,
                    settings.bgHigh,
                ) { latest, recent, unit, bgLow, bgHigh ->
                    DrawParams(latest, recent, unit, bgLow.toDouble(), bgHigh.toDouble(), true)
                }.combine(settings.wallpaperShowGraph) { params, showGraph ->
                    params.copy(showGraph = showGraph)
                }.collect { params ->
                    draw(params)
                }
            }
        }

        private fun recentReadingsFlow(readingDao: ReadingDao) = flow {
            while (true) {
                val oneHourAgo = System.currentTimeMillis() - GRAPH_WINDOW_MS
                emit(readingDao.since(oneHourAgo))
                delay(RECENT_READINGS_POLL_MS)
            }
        }

        private fun stopCollecting() {
            collectJob?.cancel()
            collectJob = null
        }

        private fun formatTimeText(reading: GlucoseReading?): String {
            if (reading == null) return ""
            val ageMinutes = ((System.currentTimeMillis() - reading.ts) / MS_PER_MINUTE).toInt()
            return if (ageMinutes < 1) {
                getString(R.string.main_just_now)
            } else {
                getString(R.string.main_min_ago, ageMinutes)
            }
        }

        private fun draw(params: DrawParams) {
            if (surfaceWidth == 0 || surfaceHeight == 0) return
            val holder = surfaceHolder ?: return
            val canvas = holder.lockCanvas() ?: return
            try {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                renderer.render(
                    canvas = canvas,
                    width = surfaceWidth,
                    height = surfaceHeight,
                    reading = params.latest,
                    unit = params.unit,
                    showGraph = params.showGraph,
                    recentReadings = params.recentReadings,
                    bgLow = params.bgLow,
                    bgHigh = params.bgHigh,
                    timeText = formatTimeText(params.latest)
                )
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    private data class DrawParams(
        val latest: GlucoseReading?,
        val recentReadings: List<GlucoseReading>,
        val unit: GlucoseUnit,
        val bgLow: Double,
        val bgHigh: Double,
        val showGraph: Boolean
    )

    companion object {
        private const val GRAPH_WINDOW_MS = 3_600_000L
        private const val RECENT_READINGS_POLL_MS = 60_000L
    }
}
