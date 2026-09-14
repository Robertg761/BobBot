package com.bobbot.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.bobbot.core.net.HermesApi
import com.bobbot.core.net.asString
import com.bobbot.core.net.json
import com.bobbot.core.net.list
import com.bobbot.core.net.obj
import com.bobbot.core.net.str
import com.bobbot.core.net.toJson
import com.bobbot.data.prefs.AppPrefs
import com.bobbot.data.repo.AutomationsRepository
import com.bobbot.data.repo.BoardRepository
import com.bobbot.data.repo.BotsRepository
import com.bobbot.data.repo.ChatRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * The always-on link to Hermes. A foreground service so the OS lets us hold a socket and a
 * long-poll open while the app is backgrounded. Independent watchers run under one
 * supervisor: each one catches its own failures and retries with backoff so a dead board
 * plugin can never take down ntfy push.
 */
@AndroidEntryPoint
class LinkService : Service() {

    @Inject lateinit var prefs: AppPrefs
    @Inject lateinit var api: HermesApi
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var okHttp: OkHttpClient
    @Inject lateinit var chat: ChatRepository
    @Inject lateinit var board: BoardRepository
    @Inject lateinit var automations: AutomationsRepository
    @Inject lateinit var bots: BotsRepository

    companion object {
        const val ACTION_START = "com.bobbot.service.LinkService.START"
        const val ACTION_STOP = "com.bobbot.service.LinkService.STOP"

        private const val TAG = "LinkService"
        private val ACTIVITY_KINDS = setOf("assigned", "commented", "completed", "blocked")

        /** Cheap, good-enough liveness flag for the settings screen. */
        @Volatile
        var isRunning: Boolean = false

        fun start(ctx: Context) {
            val i = Intent(ctx.applicationContext, LinkService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(ctx.applicationContext, i) }
                .onFailure { Log.w(TAG, "could not start link service", it) }
        }

        fun stop(ctx: Context) {
            val app = ctx.applicationContext
            val i = Intent(app, LinkService::class.java).setAction(ACTION_STOP)
            runCatching { app.startService(i) }.onFailure {
                runCatching { app.stopService(Intent(app, LinkService::class.java)) }
            }
        }
    }

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val status = MutableStateFlow("Connecting to Hermes…")

    private var watchersStarted = false
    @Volatile private var ntfyCall: Call? = null

    /** Board activity ids already turned into a notification, so a re-fetch never double-posts. */
    private val seenActivity = LinkedHashSet<String>()

    private val streamClient: OkHttpClient by lazy {
        okHttp.newBuilder()
            .readTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must happen before anything else, including on the stop path:
        // the system expects it within a few seconds of startForegroundService().
        goForeground(status.value)

        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        if (!watchersStarted) {
            watchersStarted = true
            isRunning = true
            launchWatchers()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { ntfyCall?.cancel() }
        supervisor.cancel()
        super.onDestroy()
    }

    // ---- lifecycle helpers ----

    private fun goForeground(text: String) {
        val n = notifier.linkOngoing(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(Notifier.ID_LINK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(Notifier.ID_LINK, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
        }
    }

    private fun shutdown() {
        isRunning = false
        runCatching { ntfyCall?.cancel() }
        supervisor.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun launchWatchers() {
        scope.launch { statusWatcher() }
        scope.launch { enabledWatcher() }
        scope.launch { gatewayKeeper() }
        scope.launch { completionsWatcher() }
        scope.launch { ntfyWatcher() }
        scope.launch { cronWatcher() }
        scope.launch { boardWatcher() }
    }

    /** Mirror the status flow into the ongoing notification. */
    private suspend fun statusWatcher() {
        var last = ""
        status.collect { s ->
            if (s != last) {
                last = s
                runCatching { notifier.updateLink(s) }
            }
        }
    }

    /** If the user turns notifications off from anywhere, wind the service down. */
    private suspend fun enabledWatcher() {
        prefs.notifications.collect { n ->
            if (!n.enabled) withContext(Dispatchers.Main) { shutdown() }
        }
    }

    // ---- watcher: gateway socket ----

    private suspend fun gatewayKeeper() = resilient("gateway") { _ ->
        chat.connect()
        status.value = "Connected to Hermes · watching for bot messages"
        delay(30_000)
        0L // healthy: no extra backoff
    }

    // ---- watcher: in-app completions ----

    private suspend fun completionsWatcher() {
        try {
            chat.completions.collect { notice ->
                try {
                    if (!enabled()) return@collect
                    if (appInForeground()) return@collect
                    notifier.botMessage(
                        bot = notice.profile,
                        title = notice.title.ifBlank { notice.profile },
                        text = notice.preview,
                        sessionId = notice.storedId,
                        profile = notice.profile,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "completion notice failed", e)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "completions collector died", e)
        }
    }

    private suspend fun appInForeground(): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }.getOrDefault(false)
    }

    // ---- watcher: ntfy stream ----

    private suspend fun ntfyWatcher() = resilient("ntfy") { _ ->
        val n = prefs.currentNotifications()
        if (!n.enabled || n.ntfyTopic.isBlank()) return@resilient 15_000L

        val url = "${n.ntfyServer.trimEnd('/')}/${n.ntfyTopic.trim()}/json"
        val req = Request.Builder().url(url).get().apply {
            if (n.ntfyToken.isNotBlank()) header("Authorization", "Bearer ${n.ntfyToken.trim()}")
        }.build()

        val call = streamClient.newCall(req)
        ntfyCall = call
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("ntfy responded ${resp.code}")
            val source = resp.body?.source() ?: throw IOException("ntfy sent an empty body")
            status.value = "Connected to Hermes · push on ${n.ntfyTopic}"
            while (scope.isActive) {
                val line = source.readUtf8Line() ?: break // EOF: fall through and reconnect
                if (line.isBlank()) continue
                onNtfyLine(line)
            }
        }
        3_000L
    }

    private fun onNtfyLine(line: String) {
        val el = runCatching { json.parseToJsonElement(line) }.getOrNull() ?: return
        when (el.str("event")) {
            "message" -> {
                val message = el.str("message")?.takeIf { it.isNotBlank() } ?: return
                if (message.contains("[SILENT]")) return
                val bot = el.list("tags").mapNotNull { it.asString() }
                    .firstOrNull { it.isNotBlank() } ?: "Hermes"
                notifier.botMessage(
                    bot = bot,
                    title = el.str("title")?.takeIf { it.isNotBlank() } ?: "Hermes",
                    text = message,
                    sessionId = null,
                    profile = null,
                )
            }
            // "open" / "keepalive" / "poll_request" carry no payload worth surfacing.
            else -> Unit
        }
    }

    // ---- watcher: cron ----

    private suspend fun cronWatcher() = resilient("cron") { _ ->
        val n = prefs.currentNotifications()
        if (n.enabled && n.watchCron) checkCron()
        90_000L
    }

    private suspend fun checkCron() {
        val jobs = automations.refresh()
        val stored = prefs.lastCronSeen.first()
        val seen = decodeMap(stored).toMutableMap()
        // A blank store means this is the first sync on this device: remember where we are
        // instead of notifying about every historical run.
        val priming = stored.isBlank()
        var changed = false

        for (job in jobs) {
            val last = job.lastRunAt?.takeIf { it.isNotBlank() } ?: continue
            if (seen[job.id] == last) continue
            val firstSightOfJob = !seen.containsKey(job.id)
            seen[job.id] = last
            changed = true
            if (priming || firstSightOfJob) continue

            val failed = (job.lastStatus ?: "").equals("error", ignoreCase = true) || !job.lastError.isNullOrBlank()
            // Hermes already delivers these runs to a real channel (Telegram, Discord, ntfy…);
            // repeating them here is just noise. Failures are still worth a heads-up.
            val deliversElsewhere = job.deliver.isNotBlank() && !job.deliver.equals("local", ignoreCase = true)
            if (deliversElsewhere && !failed) continue

            val run = runCatching { automations.runs(job, 1).firstOrNull() }.getOrNull()
            val output = run?.output?.takeIf { it.isNotBlank() }
            if (failed) {
                notifier.automation(job.name, job.profile, job.lastError?.takeIf { it.isNotBlank() } ?: run?.error ?: "The run failed", ok = false)
                continue
            }
            // No reply, or the job explicitly chose silence: nothing to report.
            if (output == null || output.contains("[SILENT]")) continue
            notifier.automation(job.name, job.profile, output, ok = true)
        }
        if (changed) prefs.setLastCronSeen(encodeMap(seen))
    }

    private fun decodeMap(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            json.parseToJsonElement(raw).obj
                ?.mapNotNull { (k, v) -> v.asString()?.let { k to it } }
                ?.toMap()
                .orEmpty()
        }.getOrDefault(emptyMap())
    }

    private fun encodeMap(map: Map<String, String>): String =
        runCatching { toJson(map).toString() }.getOrDefault("{}")

    // ---- watcher: board ----

    private suspend fun boardWatcher() = resilient("board") { _ ->
        val n = prefs.currentNotifications()
        if (n.enabled && n.watchBoard) checkBoard()
        60_000L
    }

    private suspend fun checkBoard() {
        val activity = board.refreshActivity(30)
        if (activity.isEmpty()) return

        val cursor = prefs.lastBoardCursor.first()
        val priming = cursor == 0L
        var newest = cursor

        // Oldest first so notifications arrive in the order the bots spoke.
        for (a in activity.asReversed()) {
            val at = parseIsoMillis(a.at)
            if (at > newest) newest = at
            val fresh = seenActivity.add(a.id)
            if (priming || !fresh) continue
            if (at <= cursor) continue
            if (a.kind !in ACTIVITY_KINDS) continue
            val from = a.from?.takeIf { it.isNotBlank() } ?: continue
            val to = a.to?.takeIf { it.isNotBlank() } ?: continue
            notifier.botToBot(from, to, a.text)
        }

        if (seenActivity.size > 600) {
            val keep = activity.map { it.id }.toSet()
            seenActivity.retainAll(keep)
        }
        if (newest > cursor) prefs.setLastBoardCursor(newest)
    }

    /** ISO-8601 in whatever shape Hermes felt like emitting. Never throws. */
    private fun parseIsoMillis(raw: String?): Long {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return System.currentTimeMillis()
        val epoch = s.toDoubleOrNull()
        if (epoch != null) return if (epoch > 1e11) epoch.toLong() else (epoch * 1000).toLong()

        val normalized = s.replace(' ', 'T')
        try {
            return OffsetDateTime.parse(normalized).toInstant().toEpochMilli()
        } catch (_: Throwable) {
        }
        try {
            return Instant.parse(normalized).toEpochMilli()
        } catch (_: Throwable) {
        }
        try {
            return Instant.parse(if (normalized.endsWith("Z")) normalized else normalized + "Z").toEpochMilli()
        } catch (_: Throwable) {
        }
        return System.currentTimeMillis()
    }

    // ---- shared retry harness ----

    /**
     * Runs [block] forever. The block returns how long to wait before the next pass; a throw
     * is logged and retried with exponential backoff instead of killing the watcher.
     */
    private suspend fun resilient(name: String, block: suspend (attempt: Int) -> Long) {
        var attempt = 0
        var backoff = 3_000L
        while (scope.isActive) {
            try {
                val wait = block(attempt)
                attempt = 0
                backoff = 3_000L
                if (wait > 0) delay(wait)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                attempt++
                Log.w(TAG, "$name watcher failed (attempt $attempt)", e)
                if (name == "gateway" || name == "ntfy") status.value = "Reconnecting to Hermes…"
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(120_000L)
            }
        }
    }

    private suspend fun enabled(): Boolean =
        runCatching { prefs.currentNotifications().enabled }.getOrDefault(true)
}
