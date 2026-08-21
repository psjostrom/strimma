package com.psjostrom.strimma.data.workout

data class AlertProtocol(
    val urgentLowEnabled: Boolean,
    val lowEnabled: Boolean,
    val highEnabled: Boolean,
    val urgentHighEnabled: Boolean,
    val urgentLowMgdl: Float,
    val lowMgdl: Float,
    val highMgdl: Float,
    val urgentHighMgdl: Float,
    val lowSoonEnabled: Boolean,
    val highSoonEnabled: Boolean,
    val staleEnabled: Boolean,
) {
    fun hasOrderedThresholds(): Boolean =
        urgentLowMgdl <= lowMgdl && lowMgdl <= highMgdl && highMgdl <= urgentHighMgdl
}
