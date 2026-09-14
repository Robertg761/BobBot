package com.bobbot.core

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on disk so it can be shared from Settings. Until now a crash could only be
 * diagnosed with the phone on a cable; a report the user can send from the phone closes that gap.
 */
object CrashLog {
    private const val DIR = "crash"
    private const val FILE = "last-crash.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun file(ctx: Context): File = File(File(ctx.filesDir, DIR), FILE)

    /** "Crashed 14 Sep, 17:02" or null when nothing is recorded. */
    fun summary(ctx: Context): String? {
        val f = file(ctx)
        if (!f.isFile) return null
        return "Crashed " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
    }

    fun clear(ctx: Context) { runCatching { file(ctx).delete() } }

    /** A share chooser carrying the report as text and as an attachment, or null when there is none. */
    fun shareIntent(ctx: Context): Intent? {
        val f = file(ctx)
        if (!f.isFile) return null
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "BobBot crash report")
            putExtra(Intent.EXTRA_TEXT, runCatching { f.readText().take(6000) }.getOrDefault(""))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Send crash report")
    }

    private fun write(ctx: Context, thread: Thread, error: Throwable) {
        val f = file(ctx)
        f.parentFile?.mkdirs()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        f.writeText(
            buildString {
                appendLine("BobBot ${version?.versionName ?: "?"} (${version?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it) } ?: "?"})")
                appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("At ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())} on thread ${thread.name}")
                appendLine()
                append(trace)
            },
        )
    }
}
