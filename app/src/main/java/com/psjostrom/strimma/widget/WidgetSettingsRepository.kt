package com.psjostrom.strimma.widget

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOpacity(): Float = prefs.getFloat(KEY_OPACITY, DEFAULT_OPACITY)
    fun getGraphMinutes(): Int = prefs.getInt(KEY_GRAPH_MINUTES, DEFAULT_GRAPH_MINUTES)
    fun getShowPrediction(): Boolean = prefs.getBoolean(KEY_SHOW_PREDICTION, DEFAULT_SHOW_PREDICTION)

    fun save(opacity: Float, graphMinutes: Int, showPrediction: Boolean) {
        prefs.edit()
            .putFloat(KEY_OPACITY, opacity)
            .putInt(KEY_GRAPH_MINUTES, graphMinutes)
            .putBoolean(KEY_SHOW_PREDICTION, showPrediction)
            .commit()
    }

    fun exportToJson(): JSONObject = JSONObject().apply {
        put(KEY_OPACITY, getOpacity().toDouble())
        put(KEY_GRAPH_MINUTES, getGraphMinutes())
        put(KEY_SHOW_PREDICTION, getShowPrediction())
    }

    fun importFromJson(widget: JSONObject) {
        prefs.edit().apply {
            if (widget.has(KEY_OPACITY))
                putFloat(KEY_OPACITY, widget.getDouble(KEY_OPACITY).toFloat().coerceIn(0f, 1f))
            if (widget.has(KEY_GRAPH_MINUTES)) {
                val mins = widget.getInt(KEY_GRAPH_MINUTES)
                if (mins in VALID_GRAPH_MINUTES) putInt(KEY_GRAPH_MINUTES, mins)
            }
            if (widget.has(KEY_SHOW_PREDICTION))
                putBoolean(KEY_SHOW_PREDICTION, widget.getBoolean(KEY_SHOW_PREDICTION))
            apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "widget_prefs"
        private const val KEY_OPACITY = "opacity"
        private const val KEY_GRAPH_MINUTES = "graph_minutes"
        private const val KEY_SHOW_PREDICTION = "show_prediction"

        const val DEFAULT_OPACITY = 0.85f
        const val DEFAULT_GRAPH_MINUTES = 60
        const val DEFAULT_SHOW_PREDICTION = false

        private val VALID_GRAPH_MINUTES = setOf(30, 60, 120, 180)
    }
}
