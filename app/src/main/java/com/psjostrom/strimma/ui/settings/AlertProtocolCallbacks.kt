package com.psjostrom.strimma.ui.settings

data class AlertProtocolCallbacks(
    val onUrgentLowEnabledChange: (Boolean) -> Unit,
    val onLowEnabledChange: (Boolean) -> Unit,
    val onHighEnabledChange: (Boolean) -> Unit,
    val onUrgentHighEnabledChange: (Boolean) -> Unit,
    val onUrgentLowChange: (Float) -> Unit,
    val onLowChange: (Float) -> Unit,
    val onHighChange: (Float) -> Unit,
    val onUrgentHighChange: (Float) -> Unit,
    val onLowSoonEnabledChange: (Boolean) -> Unit,
    val onHighSoonEnabledChange: (Boolean) -> Unit,
    val onStaleEnabledChange: (Boolean) -> Unit,
)
