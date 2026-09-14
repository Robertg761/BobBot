package com.bobbot.update

data class UpdateInfo(
    val latestVersionName: String,
    val apkName: String,
    val apkUrl: String,
    val releaseNotes: String = "",
    val releaseUrl: String = "",
    val apkSizeBytes: Long? = null,
    val publishedAt: String? = null,
)
