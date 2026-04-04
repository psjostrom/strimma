package com.psjostrom.strimma.network

sealed class IntegrationStatus {
    data object Idle : IntegrationStatus()
    data object Connecting : IntegrationStatus()
    data class Connected(val lastActivityTs: Long) : IntegrationStatus()
    data class Error(val message: String) : IntegrationStatus()
}
