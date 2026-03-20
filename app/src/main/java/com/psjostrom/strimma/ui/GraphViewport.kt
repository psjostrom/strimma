package com.psjostrom.strimma.ui

internal data class GraphViewport(
    val visibleStart: Long,
    val visibleMs: Long,
    val bgLow: Double,
    val bgHigh: Double,
    val canvasWidth: Float,
    val canvasHeight: Float,
    val marginLeft: Float
) {
    val plotWidth get() = canvasWidth - marginLeft - GRAPH_MARGIN_RIGHT
    val plotHeight get() = canvasHeight - GRAPH_MARGIN_TOP - GRAPH_MARGIN_BOTTOM
}
