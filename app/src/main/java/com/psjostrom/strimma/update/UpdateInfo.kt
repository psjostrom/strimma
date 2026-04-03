package com.psjostrom.strimma.update

data class UpdateInfo(
    val version: String,
    val changelog: String,
    val apkUrl: String,
    val isForced: Boolean
)
