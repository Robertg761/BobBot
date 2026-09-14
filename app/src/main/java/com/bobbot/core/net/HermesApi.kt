package com.bobbot.core.net

import com.bobbot.data.model.Bot
import com.bobbot.data.model.BoardComment
import com.bobbot.data.model.BoardEvent
import com.bobbot.data.model.BoardTask
import com.bobbot.data.model.CronJob
import com.bobbot.data.model.CronRun
import com.bobbot.data.model.MessagingPlatform
import com.bobbot.data.model.ModelProvider
import com.bobbot.data.model.ServerStatus
import com.bobbot.data.model.SessionSummary
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Typed REST calls against the Hermes dashboard. Profile scoping is a query param + body field. */
@Singleton
class HermesApi @Inject constructor(val http: HermesClient) {

    // ---- health / status ----
    suspend fun health(): JsonElement = http.get("/api/health", auth = false)
    suspend fun status(profile: String? = null): ServerStatus = ServerStatus.from(http.get("/api/status", mapOf("profile" to profile), auth = false))
    suspend fun statusRaw(profile: String? = null): JsonElement = http.get("/api/status", mapOf("profile" to profile), auth = false)
    suspend fun systemStats(): JsonElement = http.get("/api/system/stats")
    suspend fun me(): JsonElement = http.get("/api/auth/me")
    suspend fun analyticsUsage(days: Int = 30): JsonElement = http.get("/api/analytics/usage", mapOf("days" to "$days"))
    suspend fun analyticsModels(days: Int = 30): JsonElement = http.get("/api/analytics/models", mapOf("days" to "$days"))
    suspend fun logs(file: String = "agent", lines: Int = 200, level: String? = null): JsonElement =
        http.get("/api/logs", mapOf("file" to file, "lines" to "$lines", "level" to level))

    suspend fun gatewayAction(action: String, profile: String? = null): JsonElement =
        http.post("/api/gateway/$action", query = mapOf("profile" to profile))

    // ---- profiles (bots) ----
    suspend fun bots(): List<Bot> = http.get("/api/profiles").let { r ->
        (r.list("profiles").ifEmpty { r.arr?.toList() ?: emptyList() }).map(Bot::from)
    }
    suspend fun createBot(body: JsonObject): JsonElement = http.post("/api/profiles", body)
    suspend fun deleteBot(name: String): JsonElement = http.delete("/api/profiles/$name")
    suspend fun renameBot(name: String, newName: String): JsonElement = http.patch("/api/profiles/$name", jsonOf("new_name" to newName))
    suspend fun soul(name: String): String = http.get("/api/profiles/$name/soul").str("content") ?: ""
    suspend fun setSoul(name: String, content: String): JsonElement = http.put("/api/profiles/$name/soul", jsonOf("content" to content))
    suspend fun setBotDescription(name: String, d: String): JsonElement = http.put("/api/profiles/$name/description", jsonOf("description" to d))
    suspend fun setBotModel(name: String, provider: String, model: String): JsonElement =
        http.put("/api/profiles/$name/model", jsonOf("provider" to provider, "model" to model))
    suspend fun describeBotAuto(name: String): JsonElement = http.post("/api/profiles/$name/describe-auto", jsonOf("overwrite" to true))
    suspend fun activeProfile(): JsonElement = http.get("/api/profiles/active")

    // ---- sessions ----
    suspend fun sessions(profile: String?, limit: Int = 50, offset: Int = 0, order: String = "recent", archived: String = "exclude", excludeSources: String? = "cron"): List<SessionSummary> =
        http.get("/api/sessions", mapOf("profile" to profile, "limit" to "$limit", "offset" to "$offset", "order" to order, "archived" to archived, "exclude_sources" to excludeSources))
            .list("sessions").map(SessionSummary::from)
    suspend fun searchSessions(profile: String?, q: String, limit: Int = 50): JsonElement =
        http.get("/api/sessions/search", mapOf("profile" to profile, "q" to q, "limit" to "$limit"))
    suspend fun sessionMessages(profile: String?, id: String, limit: Int = 500, offset: Int = 0): JsonElement =
        http.get("/api/sessions/$id/messages", mapOf("profile" to profile, "limit" to "$limit", "offset" to "$offset"))
    suspend fun patchSession(profile: String?, id: String, title: String? = null, archived: Boolean? = null, pinned: Boolean? = null): JsonElement =
        http.patch("/api/sessions/$id", jsonOf("title" to title, "archived" to archived, "pinned" to pinned, "profile" to profile), mapOf("profile" to profile))
    suspend fun deleteSession(profile: String?, id: String): JsonElement =
        http.delete("/api/sessions/$id", mapOf("profile" to profile))
    suspend fun latestDescendant(profile: String?, id: String): String =
        http.get("/api/sessions/$id/latest-descendant", mapOf("profile" to profile)).str("session_id") ?: id

    // ---- models ----
    suspend fun modelInfo(profile: String? = null): JsonElement = http.get("/api/model/info", mapOf("profile" to profile), auth = false)
    suspend fun modelOptions(profile: String? = null, includeUnconfigured: Boolean = false): Pair<List<ModelProvider>, Pair<String, String>> {
        val r = http.get("/api/model/options", mapOf("profile" to profile, "include_unconfigured" to if (includeUnconfigured) "1" else null))
        return r.list("providers").map(ModelProvider::from) to ((r.str("model") ?: "") to (r.str("provider") ?: ""))
    }
    suspend fun setModel(provider: String, model: String, profile: String? = null, confirm: Boolean = false, scope: String = "main", task: String = ""): JsonElement =
        http.post("/api/model/set", jsonOf("scope" to scope, "provider" to provider, "model" to model, "task" to task, "confirm_expensive_model" to confirm, "profile" to profile), mapOf("profile" to profile))
    suspend fun auxiliaryModels(profile: String? = null): JsonElement = http.get("/api/model/auxiliary", mapOf("profile" to profile))
    suspend fun moa(profile: String? = null): JsonElement = http.get("/api/model/moa", mapOf("profile" to profile))

    // ---- config / env ----
    suspend fun config(profile: String? = null): JsonElement = http.get("/api/config", mapOf("profile" to profile))
    suspend fun putConfig(patch: JsonObject, profile: String? = null): JsonElement = http.put("/api/config", patch, mapOf("profile" to profile))
    suspend fun env(profile: String? = null): JsonElement = http.get("/api/env", mapOf("profile" to profile))
    suspend fun putEnv(vars: Map<String, String>, profile: String? = null): JsonElement =
        http.put("/api/env", jsonOf("env" to vars, "profile" to profile), mapOf("profile" to profile))

    // ---- messaging platforms ----
    suspend fun platforms(profile: String? = null): List<MessagingPlatform> =
        http.get("/api/messaging/platforms", mapOf("profile" to profile)).list("platforms").map(MessagingPlatform::from)
    suspend fun updatePlatform(id: String, enabled: Boolean, env: Map<String, String>, profile: String? = null): JsonElement =
        http.put("/api/messaging/platforms/$id", jsonOf("enabled" to enabled, "env" to env, "profile" to profile), mapOf("profile" to profile))
    suspend fun testPlatform(id: String, profile: String? = null): JsonElement =
        http.post("/api/messaging/platforms/$id/test", query = mapOf("profile" to profile))

    // ---- cron ----
    suspend fun cronJobs(profile: String? = null): List<CronJob> =
        http.get("/api/cron/jobs", mapOf("profile" to profile)).let { r -> (r.list("jobs").ifEmpty { r.arr?.toList() ?: emptyList() }).map(CronJob::from) }
    suspend fun cronRuns(id: String, profile: String? = null, limit: Int = 20): List<CronRun> =
        http.get("/api/cron/jobs/$id/runs", mapOf("profile" to profile, "limit" to "$limit")).let { r ->
            (r.list("runs").ifEmpty { r.list("executions").ifEmpty { r.arr?.toList() ?: emptyList() } }).map { CronRun.from(it, id) }
        }
    suspend fun cronDeliveryTargets(profile: String? = null): JsonElement = http.get("/api/cron/delivery-targets", mapOf("profile" to profile))
    suspend fun createCron(body: JsonObject, profile: String? = null): JsonElement = http.post("/api/cron/jobs", body, mapOf("profile" to profile))
    suspend fun updateCron(id: String, body: JsonObject, profile: String? = null): JsonElement = http.put("/api/cron/jobs/$id", body, mapOf("profile" to profile))
    suspend fun cronAction(id: String, action: String, profile: String? = null): JsonElement = http.post("/api/cron/jobs/$id/$action", query = mapOf("profile" to profile))
    suspend fun deleteCron(id: String, profile: String? = null): JsonElement = http.delete("/api/cron/jobs/$id", mapOf("profile" to profile))

    // ---- kanban board (bot-to-bot) ----
    private val kb = "/api/plugins/kanban"
    suspend fun board(): JsonElement = http.get("$kb/board")
    suspend fun boardTasks(): List<BoardTask> = board().let { r ->
        val direct = r.list("tasks")
        val fromColumns = r.child("columns").obj?.values?.flatMap { it.list("tasks").ifEmpty { it.arr?.toList() ?: emptyList() } } ?: emptyList()
        val fromColumnArr = r.list("columns").flatMap { it.list("tasks") }
        (direct.ifEmpty { fromColumns.ifEmpty { fromColumnArr } }).map(BoardTask::from)
    }
    suspend fun boardTask(id: String): JsonElement = http.get("$kb/tasks/$id")
    suspend fun boardTaskComments(id: String): List<BoardComment> = boardTask(id).let { r ->
        (r.list("comments").ifEmpty { r.child("task").list("comments") }).map(BoardComment::from)
    }
    suspend fun createBoardTask(title: String, description: String, assignee: String?, createdBy: String?): JsonElement =
        http.post("$kb/tasks", jsonOf("title" to title, "description" to description, "assignee" to assignee, "created_by" to createdBy))
    suspend fun commentBoardTask(id: String, body: String, author: String?): JsonElement =
        http.post("$kb/tasks/$id/comments", jsonOf("body" to body, "author" to author))
    suspend fun patchBoardTask(id: String, patch: JsonObject): JsonElement = http.patch("$kb/tasks/$id", patch)
    suspend fun boardAssignees(): List<String> = http.get("$kb/assignees").let { r ->
        (r.list("assignees").ifEmpty { r.list("profiles").ifEmpty { r.arr?.toList() ?: emptyList() } }).mapNotNull { it.str("name") ?: it.asString() }
    }
    suspend fun boardEventsSince(cursor: Long): List<BoardEvent> =
        runCatching { http.get("$kb/events", mapOf("cursor" to "$cursor")).list("events").map(BoardEvent::from) }.getOrDefault(emptyList())
    suspend fun boardRuns(): JsonElement = http.get("$kb/workers/active")

    // ---- skills / tools ----
    suspend fun skills(profile: String? = null): JsonElement = http.get("/api/skills", mapOf("profile" to profile))
    suspend fun toggleSkill(name: String, enabled: Boolean, profile: String? = null): JsonElement =
        http.put("/api/skills/toggle", jsonOf("name" to name, "enabled" to enabled, "profile" to profile), mapOf("profile" to profile))
    suspend fun toolsets(profile: String? = null): JsonElement = http.get("/api/tools/toolsets", mapOf("profile" to profile))

    // ---- media ----
    suspend fun mediaDataUrl(path: String): String? = http.get("/api/media", mapOf("path" to path)).str("data_url")
}
