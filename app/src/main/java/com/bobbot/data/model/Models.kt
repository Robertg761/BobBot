package com.bobbot.data.model

import com.bobbot.core.net.asString
import com.bobbot.core.net.bool
import com.bobbot.core.net.child
import com.bobbot.core.net.dbl
import com.bobbot.core.net.int
import com.bobbot.core.net.list
import com.bobbot.core.net.long
import com.bobbot.core.net.obj
import com.bobbot.core.net.str
import kotlinx.serialization.json.JsonElement

/** A bot = a Hermes profile (its own SOUL.md, model, skills, sessions). */
data class Bot(
    val name: String,
    val isDefault: Boolean,
    val model: String,
    val provider: String,
    val description: String,
    val skillCount: Int,
    val gatewayRunning: Boolean,
    val distributionName: String?,
) {
    val displayName: String get() = name
    companion object {
        fun from(j: JsonElement): Bot = Bot(
            name = j.str("name") ?: "default",
            isDefault = j.bool("is_default") ?: false,
            model = j.str("model") ?: "",
            provider = j.str("provider") ?: "",
            description = j.str("description") ?: "",
            skillCount = j.int("skill_count") ?: 0,
            gatewayRunning = j.bool("gateway_running") ?: false,
            distributionName = j.str("distribution_name"),
        )
    }
}

data class SessionSummary(
    val id: String,
    val title: String,
    val preview: String,
    val model: String,
    val source: String,
    val startedAt: Double,
    val lastActive: Double,
    val messageCount: Int,
    val archived: Boolean,
    val pinned: Boolean,
    val profile: String,
    val isActive: Boolean,
) {
    companion object {
        fun from(j: JsonElement): SessionSummary = SessionSummary(
            id = j.str("id") ?: "",
            title = j.str("title") ?: "",
            preview = j.str("preview") ?: "",
            model = j.str("model") ?: "",
            source = j.str("source") ?: "",
            startedAt = j.dbl("started_at") ?: 0.0,
            lastActive = j.dbl("last_active") ?: j.dbl("started_at") ?: 0.0,
            messageCount = j.int("message_count") ?: 0,
            archived = j.bool("archived") ?: false,
            pinned = j.bool("pinned") ?: false,
            profile = j.str("profile") ?: "default",
            isActive = j.bool("is_active") ?: false,
        )
    }
}

data class ModelEntry(val id: String, val fast: Boolean, val reasoning: Boolean, val free: Boolean)

data class ModelProvider(
    val slug: String,
    val name: String,
    val isCurrent: Boolean,
    val authenticated: Boolean?,
    val models: List<ModelEntry>,
    val featured: List<String>,
    val warning: String?,
    val source: String,
) {
    companion object {
        fun from(j: JsonElement): ModelProvider {
            val caps = j.child("capabilities").obj
            val pricing = j.child("pricing").obj
            val models = j.list("models").mapNotNull { m ->
                val id = m.asString() ?: return@mapNotNull null
                val c = caps?.get(id)
                ModelEntry(
                    id = id,
                    fast = c.bool("fast") ?: false,
                    reasoning = c.bool("reasoning") ?: false,
                    free = pricing?.get(id).bool("free") ?: false,
                )
            }
            return ModelProvider(
                slug = j.str("slug") ?: "",
                name = j.str("name") ?: (j.str("slug") ?: ""),
                isCurrent = j.bool("is_current") ?: false,
                authenticated = j.bool("authenticated"),
                models = models,
                featured = j.list("featured_models").mapNotNull { it.asString() },
                warning = j.str("warning"),
                source = j.str("source") ?: "",
            )
        }
    }
}

data class CronJob(
    val id: String,
    val name: String,
    val prompt: String,
    val scheduleDisplay: String,
    val deliver: String,
    val enabled: Boolean,
    val profile: String,
    val lastRunAt: String?,
    val nextRunAt: String?,
    val lastStatus: String?,
    val lastError: String?,
    val state: String?,
    val raw: JsonElement,
) {
    companion object {
        fun from(j: JsonElement): CronJob {
            val sch = j.child("schedule")
            val display = sch.str("display") ?: sch.str("expr") ?: sch.int("minutes")?.let { "every $it min" } ?: sch?.asString() ?: ""
            return CronJob(
                id = j.str("id") ?: "",
                name = j.str("name") ?: "(unnamed)",
                prompt = j.str("prompt") ?: "",
                scheduleDisplay = display,
                deliver = j.str("deliver") ?: "local",
                enabled = j.bool("enabled") ?: true,
                profile = j.str("profile") ?: "default",
                lastRunAt = j.str("last_run_at"),
                nextRunAt = j.str("next_run_at"),
                lastStatus = j.str("last_status"),
                lastError = j.str("last_error"),
                state = j.str("state"),
                raw = j,
            )
        }
    }
}

data class CronRun(
    val id: String,
    val jobId: String,
    val startedAt: String?,
    val finishedAt: String?,
    val status: String?,
    val output: String?,
    val error: String?,
) {
    companion object {
        fun from(j: JsonElement, jobId: String): CronRun = CronRun(
            id = j.str("id") ?: j.str("run_id") ?: (j.str("started_at") ?: ""),
            jobId = j.str("job_id") ?: jobId,
            startedAt = j.str("started_at") ?: j.str("fired_at"),
            finishedAt = j.str("finished_at") ?: j.str("completed_at"),
            status = j.str("status"),
            output = j.str("output") ?: j.str("result") ?: j.str("response"),
            error = j.str("error"),
        )
    }
}

data class BoardTask(
    val id: String,
    val title: String,
    val status: String,
    val assignee: String?,
    val createdBy: String?,
    val description: String,
    val createdAt: String?,
    val updatedAt: String?,
    val priority: String?,
    val raw: JsonElement,
) {
    companion object {
        fun from(j: JsonElement): BoardTask = BoardTask(
            id = j.str("id") ?: "",
            title = j.str("title") ?: "(untitled)",
            status = j.str("status") ?: j.str("column") ?: "todo",
            assignee = j.str("assignee"),
            createdBy = j.str("created_by"),
            description = j.str("description") ?: j.str("body") ?: "",
            createdAt = j.str("created_at"),
            updatedAt = j.str("updated_at"),
            priority = j.str("priority"),
            raw = j,
        )
    }
}

data class BoardComment(val id: String, val author: String, val body: String, val createdAt: String?) {
    companion object {
        fun from(j: JsonElement) = BoardComment(
            id = j.str("id") ?: "",
            author = j.str("author") ?: "unknown",
            body = j.str("body") ?: j.str("text") ?: j.str("content") ?: "",
            createdAt = j.str("created_at"),
        )
    }
}

data class BoardEvent(
    val id: Long,
    val taskId: String,
    val kind: String,
    val payload: JsonElement?,
    val createdAt: String?,
) {
    val author: String? get() = payload.str("author") ?: payload.str("assignee") ?: payload.str("profile")
    companion object {
        fun from(j: JsonElement) = BoardEvent(
            id = j.long("id") ?: 0L,
            taskId = j.str("task_id") ?: "",
            kind = j.str("kind") ?: "",
            payload = j.child("payload"),
            createdAt = j.str("created_at"),
        )
    }
}

data class ServerStatus(
    val version: String,
    val gatewayRunning: Boolean,
    val gatewayState: String,
    val activeSessions: Int,
    val overall: String,
    val profiles: List<String>,
    val platforms: Map<String, String>,
    val authRequired: Boolean,
    val authFlows: List<String>,
) {
    companion object {
        fun from(j: JsonElement): ServerStatus = ServerStatus(
            version = j.str("version") ?: "?",
            gatewayRunning = j.bool("gateway_running") ?: false,
            gatewayState = j.str("gateway_state") ?: "unknown",
            activeSessions = j.int("active_sessions") ?: 0,
            overall = j.str("overall") ?: "unknown",
            profiles = j.list("profiles").mapNotNull { it.asString() },
            platforms = j.child("gateway_platforms").obj?.mapValues { it.value.str("state") ?: "?" } ?: emptyMap(),
            authRequired = j.bool("auth_required") ?: true,
            authFlows = j.list("auth_flows").mapNotNull { it.asString() },
        )
    }
}

data class MessagingPlatform(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val configured: Boolean,
    val state: String,
    val envVars: List<String>,
) {
    companion object {
        fun from(j: JsonElement) = MessagingPlatform(
            id = j.str("id") ?: "",
            name = j.str("name") ?: (j.str("id") ?: ""),
            enabled = j.bool("enabled") ?: false,
            configured = j.bool("configured") ?: false,
            state = j.str("state") ?: "",
            envVars = j.list("env_vars").mapNotNull { it.str("name") ?: it.asString() },
        )
    }
}
