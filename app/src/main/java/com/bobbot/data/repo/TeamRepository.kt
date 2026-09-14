package com.bobbot.data.repo

import com.bobbot.core.net.HermesApi
import com.bobbot.core.net.HermesHttpException
import com.bobbot.core.net.bool
import com.bobbot.core.net.dbl
import com.bobbot.core.net.jsonOf
import com.bobbot.core.net.list
import com.bobbot.core.net.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** One specialist action the team extension stopped for review. */
data class TeamRequest(
    val id: String,
    val profile: String,
    val tool: String,
    val args: Map<String, String>,
    val task: String,
    val status: String,      // pending | needs_user | approved | denied | consumed
    val reason: String,
    val reviewer: String,
    val scope: String,       // exact | tool
    val createdMillis: Long,
    val expiresMillis: Long,
) {
    val needsYou: Boolean get() = status == "needs_user" && !expired
    val waitingForAuthority: Boolean get() = status == "pending" && !expired
    val expired: Boolean get() = expiresMillis in 1 until System.currentTimeMillis() && status in setOf("pending", "needs_user", "approved")
    val open: Boolean get() = needsYou || waitingForAuthority

    /**
     * The argument most worth showing first: the command, the path, the query. For tools driven by an
     * `action`, the action with its arguments on one line ("remove job_id=71f6…").
     */
    val headline: String?
        get() {
            listOf("command", "path", "file_path", "url", "query", "queries").firstNotNullOfOrNull { k -> args[k]?.takeIf { it.isNotBlank() } }?.let { return it }
            args["action"]?.takeIf { it.isNotBlank() }?.let { action ->
                return (listOf(action) + args.filterKeys { it != "action" }.map { (k, v) -> "$k=${v.take(60)}" }).joinToString(" ")
            }
            return args.values.firstOrNull()
        }

    /** Arguments not already shown in the headline. */
    val details: Map<String, String>
        get() = if (args.containsKey("action") && headline?.startsWith(args["action"] ?: "\u0000") == true) emptyMap()
        else args.filterValues { it != headline }

    companion object {
        fun from(j: JsonElement): TeamRequest = TeamRequest(
            id = j.str("id") ?: "",
            profile = j.str("profile") ?: "default",
            tool = j.str("tool") ?: "tool",
            args = parseArgs(j.str("args")),
            task = j.str("task") ?: "",
            status = j.str("status") ?: "pending",
            reason = j.str("reason") ?: "",
            reviewer = j.str("reviewer") ?: "",
            scope = j.str("scope") ?: "exact",
            createdMillis = ((j.dbl("created") ?: 0.0) * 1000).toLong(),
            expiresMillis = ((j.dbl("expires") ?: 0.0) * 1000).toLong(),
        )

        /** The stored arguments are a JSON string; flatten to readable key/value pairs. */
        fun parseArgs(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            val el = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return mapOf("arguments" to raw)
            return el.entries.associate { (k, v) -> k to render(v) }
        }

        private fun render(v: JsonElement): String = when (v) {
            is JsonPrimitive -> v.content
            else -> v.toString()
        }
    }
}

data class TeamState(
    val installed: Boolean = true,
    val authority: String = "default",
    val enabled: Boolean = true,
    val profiles: List<String> = emptyList(),
    val teammateMessaging: Boolean = false,
    val requests: List<TeamRequest> = emptyList(),
) {
    val needingYou: List<TeamRequest> get() = requests.filter { it.needsYou }
    val waiting: List<TeamRequest> get() = requests.filter { it.waitingForAuthority }
    fun openFor(profile: String): List<TeamRequest> = requests.filter { it.profile == profile && it.open }
}

/**
 * The BobBot team extension's view: who the authority is, and every permission request it has
 * gated. One shared snapshot so the inbox, a bot's chat and the Team screen agree.
 */
@Singleton
class TeamRepository @Inject constructor(private val api: HermesApi) {
    private val path = "/api/plugins/bobbot-team"
    private val _state = MutableStateFlow<TeamState?>(null)
    val state: StateFlow<TeamState?> = _state

    /** Null when the extension is not installed on this server. */
    suspend fun refresh(): TeamState? {
        val r = try {
            api.http.get("$path/team")
        } catch (e: HermesHttpException) {
            if (e.code == 404) { _state.value = TeamState(installed = false); return _state.value } else throw e
        }
        val s = TeamState(
            installed = true,
            authority = r.str("authority") ?: "default",
            enabled = r.bool("enabled") ?: true,
            profiles = r.list("profiles").mapNotNull { it.str("name") },
            teammateMessaging = r.bool("teammate_messaging") ?: false,
            requests = r.list("requests").map(TeamRequest::from),
        )
        _state.value = s
        return s
    }

    /** `scope` is "exact" (this action once) or "tool" (this tool for the rest of that conversation). */
    suspend fun decide(id: String, choice: String, scope: String = "exact", reason: String = "") {
        api.http.post("$path/requests/$id/decide", jsonOf("choice" to choice, "reason" to reason, "scope" to scope))
        refresh()
    }

    suspend fun configure(authority: String, enabled: Boolean) {
        api.http.put("$path/team", jsonOf("authority" to authority, "enabled" to enabled))
        refresh()
    }

    suspend fun bootstrap(profile: String) {
        api.http.post("$path/profiles/$profile/bootstrap", jsonOf())
        refresh()
    }
}
