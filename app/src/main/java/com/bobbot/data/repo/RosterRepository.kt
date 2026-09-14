package com.bobbot.data.repo

import com.bobbot.core.net.GatewaySocket
import com.bobbot.core.net.HermesApi
import com.bobbot.core.net.bool
import com.bobbot.core.net.child
import com.bobbot.core.net.dbl
import com.bobbot.core.net.int
import com.bobbot.core.net.jsonOf
import com.bobbot.core.net.list
import com.bobbot.core.net.str
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/** A profile's canonical Bot Chat as the server reports it, plus its read and pin state. */
data class BotChat(
    val id: String,
    val resolvedId: String,
    val preview: String,
    val lastActive: Double,
    val messageCount: Int,
    val unread: Boolean = false,
    val pinned: Boolean = false,
)

/** One roster row from `profiles.list`: the bot, its ongoing chat, and whether a worker is busy for it. */
data class RosterEntry(
    val profile: String,
    val chat: BotChat?,
    /** Newest worker session heartbeat (kanban / sub-agent runs); workers beat every ≤60 s while running. */
    val workerLastActive: Double?,
    /** Hermes Bot Mode: this profile carries `ui_meta.hermes-bots`, so it appears in every bot's roster. */
    val teammateMessaging: Boolean = false,
    /** The one-line role other bots see for it. */
    val role: String = "",
) {
    fun workerBusy(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val at = workerLastActive ?: return false
        val millis = if (at > 1_000_000_000_000.0) at.toLong() else (at * 1000.0).toLong()
        return nowMillis - millis < WORKER_LIVENESS_MS
    }

    companion object {
        const val WORKER_LIVENESS_MS = 90_000L
    }
}

/**
 * The inbox's server-side truth. Hermes resolves each profile's canonical chat on every listing,
 * so no client carries a session pointer, and the same rows drive its desktop roster.
 */
@Singleton
class RosterRepository @Inject constructor(private val socket: GatewaySocket, private val api: HermesApi) {

    suspend fun roster(): List<RosterEntry> {
        socket.ensureConnected()
        val rows = socket.call("profiles.list", jsonOf("include_sessions" to true)).list("profiles")
        return coroutineScope {
            rows.map { row -> async { entry(row) } }.awaitAll()
        }
    }

    private suspend fun entry(row: JsonElement): RosterEntry {
        val name = row.str("name") ?: "default"
        val canonical = row.child("canonical_session")
        val chat = canonical?.let { c ->
            val id = c.str("id") ?: return@let null
            val resolved = c.str("resolved_id") ?: id
            val base = BotChat(
                id = id, resolvedId = resolved, preview = c.str("preview") ?: "",
                lastActive = c.dbl("last_active") ?: c.dbl("started_at") ?: 0.0,
                messageCount = c.int("message_count") ?: 0,
            )
            // Read and pin state live on the session row; a failed detail read leaves them at their defaults.
            runCatching { api.sessionDetail(name.takeIf { it != "default" }, resolved) }.getOrNull()?.let { d -> withDetail(base, d) } ?: base
        }
        val botMode = row.child("ui_meta")?.child(BOT_MODE_KEY)
        return RosterEntry(
            profile = name,
            chat = chat,
            workerLastActive = row.child("worker_session")?.dbl("last_active"),
            teammateMessaging = botMode != null,
            role = botMode.str("title") ?: "",
        )
    }

    /**
     * Turn Hermes Bot Mode on for a profile by writing the same profile metadata the Hermes desktop
     * app writes. With at least one managed profile, every Bot Chat gets the teammate roster and
     * the `message_agent` tool on its next turn.
     */
    suspend fun setTeammateMessaging(profile: String, enabled: Boolean, role: String? = null) {
        socket.ensureConnected()
        val block: Any? = if (enabled) jsonOf("title" to (role?.trim()?.take(160)?.ifBlank { null } ?: BotNames.display(profile)), "managed_by" to "bobbot") else null
        val r = socket.call("profiles.configure", jsonOf("name" to profile, "ui_meta" to jsonOf(BOT_MODE_KEY to block)))
        check(r.child("applied").bool("ui_meta") != false) { "Hermes did not accept the change" }
    }

    companion object {
        const val BOT_MODE_KEY = "hermes-bots"

        /** Unread mirrors Hermes: activity after the `last_read_at` watermark; never-tracked means read. */
        fun withDetail(chat: BotChat, detail: JsonElement): BotChat {
            val lastRead = detail.dbl("last_read_at")
            val active = detail.dbl("last_activity_at") ?: detail.dbl("last_active") ?: chat.lastActive
            val unread = detail.bool("unread") ?: (lastRead != null && active > lastRead)
            return chat.copy(
                unread = unread,
                pinned = detail.bool("pinned") ?: ((detail.int("pinned") ?: 0) != 0),
                lastActive = if (active > 0) active else chat.lastActive,
            )
        }
    }
}
