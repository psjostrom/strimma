package com.psjostrom.strimma.widget

import androidx.glance.appwidget.GlanceAppWidgetReceiver

class StrimmaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = StrimmaWidget()
}
