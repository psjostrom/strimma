package com.psjostrom.strimma.wallpaper

import android.graphics.Color
import android.graphics.PorterDuff
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class BgWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = BgWallpaperEngine()

    inner class BgWallpaperEngine : Engine() {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val renderer = BgWallpaperRenderer()
        private var collectJob: Job? = null
        private var surfaceWidth = 0
        private var surfaceHeight = 0

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
        }

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

            val now = System.currentTimeMillis()
            val oneHourAgo = now - GRAPH_WINDOW_MS

            collectJob = scope.launch {
                combine(
                    readingDao.latest(),
                    readingDao.sinceLive(oneHourAgo),
                    settings.glucoseUnit,
                    settings.bgLow,
                    settings.bgHigh,
                ) { latest, recent, unit, bgLow, bgHigh ->
                    DrawParams(latest, recent, unit, bgLow.toInt(), bgHigh.toInt(), true)
                }.combine(settings.wallpaperShowGraph) { params, showGraph ->
                    params.copy(showGraph = showGraph)
                }.collect { params ->
                    draw(params)
                }
            }
        }

        private fun stopCollecting() {
            collectJob?.cancel()
            collectJob = null
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
                    bgHigh = params.bgHigh
                )
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    private data class DrawParams(
        val latest: com.psjostrom.strimma.data.GlucoseReading?,
        val recentReadings: List<com.psjostrom.strimma.data.GlucoseReading>,
        val unit: com.psjostrom.strimma.data.GlucoseUnit,
        val bgLow: Int,
        val bgHigh: Int,
        val showGraph: Boolean
    )

    companion object {
        private const val GRAPH_WINDOW_MS = 3_600_000L
    }
}
