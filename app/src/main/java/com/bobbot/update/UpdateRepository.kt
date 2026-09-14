package com.bobbot.update

import android.content.Context
import android.util.Log
import com.bobbot.BuildConfig
import com.bobbot.core.net.asString
import com.bobbot.core.net.json
import com.bobbot.core.net.list
import com.bobbot.core.net.long
import com.bobbot.core.net.str
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Checking : UpdateCheck
    data class Available(val info: UpdateInfo) : UpdateCheck
    data class UpToDate(val checkedAt: Long) : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}

/** Checks GitHub Releases for a newer signed APK than the running build. */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
) {
    private val prefs = context.getSharedPreferences("bobbot_update_check", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateCheck>(UpdateCheck.Idle)
    val state: StateFlow<UpdateCheck> = _state

    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /** Release builds check silently on launch at most every [AUTO_CHECK_INTERVAL_MS]. */
    suspend fun autoCheck() {
        if (BuildConfig.DEBUG) return  // debug builds are signed with a different key; installs would fail
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS && _state.value !is UpdateCheck.Idle) return
        check(manual = false)
    }

    suspend fun check(manual: Boolean = true): UpdateCheck {
        _state.value = UpdateCheck.Checking
        val result = try {
            val info = fetchLatest()
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (info != null) UpdateCheck.Available(info) else UpdateCheck.UpToDate(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w("UpdateRepository", "update check failed", e)
            UpdateCheck.Failed(friendly(e))
        }
        _state.value = if (!manual && result is UpdateCheck.Failed) UpdateCheck.Idle else result
        return result
    }

    fun dismiss() { if (_state.value is UpdateCheck.Available) _state.value = UpdateCheck.Idle }

    private suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"
        val req = Request.Builder().url(url)
            .header("User-Agent", "BobBot/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = okHttp.newCall(req).execute().use { r ->
            val text = r.body.string()
            if (r.code == 404) throw IOException("No releases found (or the repository is private)")
            if (!r.isSuccessful) throw IOException("GitHub API error ${r.code}")
            text
        }
        val j = json.parseToJsonElement(body)
        val tag = j.str("tag_name")?.trim().orEmpty()
        val latest = SemVer.parseOrNull(tag) ?: return@withContext null
        val current = SemVer.parseOrNull(currentVersionName) ?: return@withContext null
        if (latest <= current) return@withContext null

        val apks = j.list("assets").mapNotNull { a ->
            val name = a.str("name")?.trim() ?: return@mapNotNull null
            val dl = a.str("browser_download_url")?.trim() ?: return@mapNotNull null
            if (!name.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
            Triple(name, dl, a.long("size"))
        }
        val (name, dl, size) = apks.firstOrNull { !it.first.contains("debug", true) } ?: apks.firstOrNull() ?: return@withContext null
        UpdateInfo(
            latestVersionName = tag.removePrefix("v"),
            apkName = name,
            apkUrl = dl,
            releaseNotes = sanitizeReleaseNotes(j.str("body").orEmpty()),
            releaseUrl = j.str("html_url").orEmpty(),
            apkSizeBytes = size,
            publishedAt = j.str("published_at"),
        )
    }

    internal fun sanitizeReleaseNotes(raw: String): String = raw
        .lineSequence()
        .map { line ->
            line.replace(Regex("<[^>]+>"), "")
                .replace(Regex("""!\[[^]]*]\([^)]*\)"""), "")
                .replace(Regex("""\[[^]]+]\([^)]*\)""")) { m -> m.value.substringAfter("[").substringBefore("]") }
                .replace(Regex("""^#{1,6}\s*"""), "")
                .replace(Regex("""^\s*[-*]\s+"""), "• ")
                .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
                .replace("`", "")
                .trimEnd()
        }
        .filter { it.isNotBlank() && !it.contains("raw.githubusercontent.com", true) }
        .joinToString("\n")
        .trim()

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection"
        else -> e.message ?: "Update check failed"
    }

    companion object {
        private const val KEY_LAST_CHECK = "last_check_at"
        private const val AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
