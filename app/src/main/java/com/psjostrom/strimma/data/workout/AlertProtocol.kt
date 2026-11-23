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
    private fun orderedPairs(): List<Boolean> =
        listOf(urgentLowMgdl <= lowMgdl, lowMgdl <= highMgdl, highMgdl <= urgentHighMgdl)

    fun hasOrderedThresholds(): Boolean = orderedPairs().all { it }

    // Imports and the Night copy can store a set out of order. Rejecting every edit that leaves the set out of order
    // would block the fix when two pairs are wrong, so an edit is rejected only when it breaks a pair that was in order.
    fun keepsOrderOf(before: AlertProtocol): Boolean =
        before.orderedPairs().zip(orderedPairs()).none { (was, now) -> was && !now }
}
