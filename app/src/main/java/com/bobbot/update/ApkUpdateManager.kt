package com.bobbot.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Downloads a release APK with DownloadManager and hands it to the package installer. */
@Singleton
class ApkUpdateManager @Inject constructor() {
    private fun prefs(context: Context) = context.getSharedPreferences("bobbot_updater", Context.MODE_PRIVATE)

    data class DownloadQueryResult(val status: Int, val bytesDownloaded: Long, val totalBytes: Long, val reason: Int?)

    fun needsUnknownSourcesPermission(context: Context): Boolean = !context.packageManager.canRequestPackageInstalls()

    fun buildUnknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun startDownload(context: Context, info: UpdateInfo): Long {
        clearDownloadedState(context, deleteApk = true)
        val fileName = "BobBot-${info.latestVersionName}.apk"
        val request = DownloadManager.Request(info.apkUrl.toUri())
            .setTitle("BobBot update")
            .setDescription("Downloading BobBot ${info.latestVersionName}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        prefs(context).edit {
            putLong(KEY_DOWNLOAD_ID, id)
            putString(KEY_APK_PATH, file.absolutePath)
            putString(KEY_VERSION, normalize(info.latestVersionName))
        }
        return id
    }

    fun queryDownload(context: Context): DownloadQueryResult? {
        val id = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)
        if (id <= 0) return null
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = runCatching { c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)) }.getOrNull()
            return DownloadQueryResult(status, soFar, total, reason)
        }
    }

    fun hasDownloadedApkForVersion(context: Context, versionName: String): Boolean {
        val file = downloadedApkFile(context) ?: return false
        val q = queryDownload(context) ?: return false
        val stored = prefs(context).getString(KEY_VERSION, null) ?: return false
        return q.status == DownloadManager.STATUS_SUCCESSFUL && file.exists() && normalize(stored) == normalize(versionName)
    }

    fun downloadedApkFile(context: Context): File? = prefs(context).getString(KEY_APK_PATH, null)?.let(::File)

    fun clearDownloadedState(context: Context, deleteApk: Boolean = false) {
        val file = downloadedApkFile(context)
        val id = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)
        if (id > 0) runCatching { (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id) }
        if (deleteApk) runCatching { if (file?.exists() == true) file.delete() }
        prefs(context).edit { remove(KEY_DOWNLOAD_ID); remove(KEY_APK_PATH); remove(KEY_VERSION) }
    }

    /** Drop a stale download once the running version has caught up. */
    fun pruneIfInstalled(context: Context, currentVersionName: String) {
        val stored = prefs(context).getString(KEY_VERSION, null) ?: return
        val d = SemVer.parseOrNull(stored); val c = SemVer.parseOrNull(currentVersionName)
        if (d != null && c != null && d <= c) clearDownloadedState(context, deleteApk = true)
    }

    fun buildInstallIntent(context: Context): Intent? {
        val file = downloadedApkFile(context) ?: return null
        if (!file.exists() || needsUnknownSourcesPermission(context)) return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun normalize(v: String) = v.trim().removePrefix("v").removePrefix("V")

    companion object {
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_APK_PATH = "apk_path"
        private const val KEY_VERSION = "downloaded_version_name"
    }
}
