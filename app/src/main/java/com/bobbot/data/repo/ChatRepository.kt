package com.bobbot.data.repo

import android.util.Log
import com.bobbot.core.net.GatewayEvent
import com.bobbot.core.net.GatewaySocket
import com.bobbot.core.net.RpcException
import com.bobbot.core.net.asString
import com.bobbot.core.net.bool
import com.bobbot.core.net.child
import com.bobbot.core.net.dbl
import com.bobbot.core.net.int
import com.bobbot.core.net.jsonOf
import com.bobbot.core.net.list
import com.bobbot.core.net.obj
import com.bobbot.core.net.prettyText
import com.bobbot.core.net.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ChatItem {
    val id: String
    /** Authoring time in epoch millis when known (history rows carry it; live items use now). */
    val at: Long? get() = null

    data class User(override val id: String, val text: String, val images: List<String> = emptyList(), override val at: Long? = null) : ChatItem
    data class Assistant(
        override val id: String,
        val text: String,
        val reasoning: String = "",
        val interim: Boolean = false,
        val streaming: Boolean = true,
        val status: String = "streaming",
        val error: String? = null,
        val usage: JsonElement? = null,
        val model: String? = null,
        override val at: Long? = null,
    ) : ChatItem
    /**
     * A message from another bot. Hermes delivers teammate DMs into a Bot Chat as user-role text
     * with a "Message from 🤖 name (@name):" prefix, and a teammate's reply comes back as a
     * background-process completion whose command names the profile. Both render as that bot.
     */
    data class Teammate(override val id: String, val profile: String, val text: String, val reply: Boolean = false, override val at: Long? = null) : ChatItem
    data class Tool(
        override val id: String,
        val toolId: String,
        val name: String,
        val context: String,
        val args: String? = null,
        val result: String? = null,
        val summary: String? = null,
        val durationS: Double? = null,
        val done: Boolean = false,
        val subagent: Boolean = false,
    ) : ChatItem
    data class System(override val id: String, val text: String, val kind: String = "status") : ChatItem
    data class Delegation(
        override val id: String,
        val subagentId: String,
        val goal: String,
        val status: String,
        val model: String? = null,
        val childSessionId: String? = null,
        val toolCount: Int = 0,
        val lastText: String? = null,
        val summary: String? = null,
    ) : ChatItem
}

data class PendingApproval(val command: String, val description: String, val choices: List<String>, val patternKey: String?)
data class PendingClarify(val requestId: String, val question: String, val choices: List<String>, val multi: Boolean)
data class PendingSecret(val requestId: String, val kind: String, val prompt: String)

data class ChatSessionState(
    val liveId: String,
    val storedId: String? = null,
    val profile: String = "default",
    val title: String = "",
    val model: String = "",
    val provider: String = "",
    val reasoningEffort: String = "",
    val approvalMode: String = "",
    val status: String = "idle", // idle | starting | working | streaming | waiting
    val items: List<ChatItem> = emptyList(),
    val approval: PendingApproval? = null,
    val clarify: PendingClarify? = null,
    val secret: PendingSecret? = null,
    val notice: String? = null,
    val statusLine: String? = null,
    val usage: JsonElement? = null,
    val error: String? = null,
    val loading: Boolean = true,
    val attachments: List<String> = emptyList(),
) {
    val isBusy: Boolean get() = status == "working" || status == "streaming" || status == "starting"
}

data class CompletionNotice(val liveId: String, val storedId: String?, val profile: String, val title: String, val preview: String, val status: String)

@Singleton
class ChatRepository @Inject constructor(private val socket: GatewaySocket) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ConcurrentHashMap<String, MutableStateFlow<ChatSessionState>>()
    private val storedToLive = ConcurrentHashMap<String, String>()

    /** Live ids currently held in memory; drives [liveStates] for the inbox. */
    private val roster = MutableStateFlow<Set<String>>(emptySet())
    private fun syncRoster() { roster.value = sessions.keys.toSet() }

    /** Every open session's state, as one list, so the inbox can show who is working. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val liveStates: Flow<List<ChatSessionState>> = roster.flatMapLatest { ids ->
        val flows = ids.mapNotNull { sessions[it] }
        if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { it.toList() }
    }

    /** Unsent composer text per conversation, kept while the app runs. */
    val drafts = ConcurrentHashMap<String, String>()

    /** Live id of the session that most recently emitted message.start. Unscoped events go here. */
    @Volatile private var focusedLive: String? = null

    private val _completions = MutableSharedFlow<CompletionNotice>(extraBufferCapacity = 64)
    val completions: SharedFlow<CompletionNotice> = _completions

    /** Events for the app-level "something changed" listeners (sessions.changed, cron.changed…). */
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val changes: SharedFlow<String> = _changes

    val socketState: StateFlow<com.bobbot.core.net.SocketState> get() = socket.state

    init {
        scope.launch { socket.events.collect { onEvent(it) } }
        scope.launch { socket.connected.collect { onReconnected() } }
    }

    fun state(liveId: String): StateFlow<ChatSessionState>? = sessions[liveId]

    fun liveFor(storedId: String): String? = storedToLive[storedId]

    suspend fun connect() = socket.ensureConnected()

    private val mainConversations = MainConversationResolver()

    /**
     * A bot's ongoing conversation is Hermes' canonical "Bot Chat": one hidden session per profile,
     * found by its exact title, shared with the Hermes desktop app and with cron `bot-chat:` delivery.
     * Task chats are ordinary sessions and stay separate.
     */
    suspend fun openMainConversation(profile: String): String = mainConversations.open(
        saved = { findMainConversation(profile) },
        live = { id -> storedToLive[id]?.takeIf { sessions.containsKey(it) } },
        latest = { id -> id },
        resume = { resumeSession(it, profile) },
        create = { val live = createSession(profile, title = MAIN_CHAT_TITLE, hidden = true); live to (sessions[live]?.value?.storedId ?: "") },
        save = { },
    )

    /** The stored id of the profile's Bot Chat (its live compression tip), or null when there is none yet. */
    suspend fun findMainConversation(profile: String): String? {
        socket.ensureConnected()
        val r = socket.call("session.list", jsonOf("profile" to profile.takeIf { it.isNotBlank() && it != "default" }, "title" to MAIN_CHAT_TITLE))
        val row = r.list("sessions").firstOrNull() ?: return null
        return row.str("resolved_id") ?: row.str("id")
    }

    /** Create a fresh session for a bot. Returns the live id. */
    suspend fun createSession(
        profile: String, model: String? = null, provider: String? = null, closeOnDisconnect: Boolean = false,
        title: String? = null, hidden: Boolean = false,
    ): String {
        socket.ensureConnected()
        val r = socket.call(
            "session.create",
            jsonOf(
                "cols" to 100,
                "profile" to profile.takeIf { it.isNotBlank() && it != "default" },
                "model" to model, "provider" to provider,
                "close_on_disconnect" to closeOnDisconnect,
                "title" to title,
                "hidden" to hidden.takeIf { it },
            ),
        )
        val live = r.str("session_id") ?: throw IllegalStateException("session.create returned no id")
        val st = MutableStateFlow(ChatSessionState(liveId = live, profile = profile, loading = false))
        sessions[live] = st
        syncRoster()
        applyInfo(live, r.child("info"))
        st.update { it.copy(storedId = r.str("stored_session_id"), loading = false) }
        r.str("stored_session_id")?.let { storedToLive[it] = live }
        return live
    }

    /** Resume a stored session (durable id). Returns the live id. */
    suspend fun resumeSession(storedId: String, profile: String): String {
        socket.ensureConnected()
        storedToLive[storedId]?.let { live -> if (sessions.containsKey(live)) return live }
        val r = socket.call(
            "session.resume",
            jsonOf("session_id" to storedId, "cols" to 100, "profile" to profile.takeIf { it.isNotBlank() && it != "default" }),
        )
        val live = r.str("session_id") ?: throw IllegalStateException("session.resume returned no id")
        val items = r.list("messages").mapNotNull { historyItem(it) }.toMutableList()
        val st = sessions.getOrPut(live) { MutableStateFlow(ChatSessionState(liveId = live, profile = profile)) }
        st.update {
            it.copy(
                storedId = r.str("resumed") ?: r.str("session_key") ?: storedId,
                items = items,
                status = r.str("status") ?: "idle",
                loading = false,
                profile = profile,
            )
        }
        applyInfo(live, r.child("info"))
        // Inflight recovery: a turn that ran while we were away.
        r.child("inflight")?.let { inf ->
            val user = inf.str("user")
            val assistant = inf.str("assistant") ?: inf.str("streaming")
            if (!user.isNullOrBlank()) st.update { s -> s.copy(items = s.items + (TeammateText.parse(user)?.let { ChatItem.Teammate(newId(), it.profile, it.text, it.reply) } ?: ChatItem.User(newId(), user))) }
            if (!assistant.isNullOrBlank()) st.update { s ->
                s.copy(items = s.items + ChatItem.Assistant(newId(), assistant, streaming = r.bool("running") ?: false, status = inf.str("status") ?: "complete", error = inf.str("error")))
            }
        }
        storedToLive[st.value.storedId ?: storedId] = live
        syncRoster()
        return live
    }

    private fun historyItem(m: JsonElement): ChatItem? {
        val role = m.str("role") ?: return null
        val text = m.str("text") ?: ""
        val kind = m.str("display_kind")
        if (kind == "hidden") return null
        val at = m.dbl("timestamp")?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
        return when (role) {
            "user" -> when (kind) {
                "model_switch", "auto_continue", "async_delegation_complete" -> ChatItem.System(newId(), text, kind)
                else -> TeammateText.parse(text)?.let { ChatItem.Teammate(newId(), it.profile, it.text, it.reply, at) }
                    ?: if (kind == "process_complete") ChatItem.System(newId(), TeammateText.processTitle(text), "background") else ChatItem.User(newId(), text, at = at)
            }
            "assistant" -> ChatItem.Assistant(newId(), text, reasoning = m.str("reasoning") ?: m.str("reasoning_content") ?: "", streaming = false, status = "complete", at = at)
            "tool" -> ChatItem.Tool(newId(), toolId = newId(), name = m.str("name") ?: "tool", context = m.str("context") ?: "", done = true)
            "system" -> ChatItem.System(newId(), text, "system")
            else -> null
        }
    }

    suspend fun send(liveId: String, text: String) {
        val st = sessions[liveId] ?: throw IllegalStateException("unknown session")
        st.update { it.copy(items = it.items + ChatItem.User(newId(), text, images = it.attachments, at = System.currentTimeMillis()), attachments = emptyList(), error = null, status = "working") }
        try {
            socket.call("prompt.submit", jsonOf("session_id" to liveId, "text" to text))
        } catch (e: RpcException) {
            st.update { it.copy(error = e.message, status = "idle") }
            throw e
        }
    }

    suspend fun interrupt(liveId: String) {
        runCatching { socket.call("session.interrupt", jsonOf("session_id" to liveId)) }
        sessions[liveId]?.update { it.copy(status = "idle", approval = null, clarify = null) }
    }

    suspend fun respondApproval(liveId: String, choice: String) {
        socket.call("approval.respond", jsonOf("session_id" to liveId, "choice" to choice, "all" to false))
        sessions[liveId]?.update { it.copy(approval = null) }
    }

    suspend fun respondClarify(liveId: String, requestId: String, answer: String) {
        socket.call("clarify.respond", jsonOf("session_id" to liveId, "request_id" to requestId, "answer" to answer))
        sessions[liveId]?.update { it.copy(clarify = null) }
    }

    suspend fun respondSecret(liveId: String, requestId: String, kind: String, value: String) {
        val (method, key) = when (kind) { "sudo" -> "sudo.respond" to "password"; else -> "secret.respond" to "value" }
        socket.call(method, jsonOf("session_id" to liveId, "request_id" to requestId, key to value))
        sessions[liveId]?.update { it.copy(secret = null) }
    }

    suspend fun attachImage(liveId: String, base64: String, filename: String) {
        val r = socket.call("image.attach_bytes", jsonOf("session_id" to liveId, "content_base64" to base64, "filename" to filename))
        val name = r.str("name") ?: filename
        sessions[liveId]?.update { it.copy(attachments = it.attachments + name) }
    }

    suspend fun setModel(liveId: String, spec: String, global: Boolean = false): JsonElement {
        val value = if (global) "$spec --global" else spec
        val r = socket.call("config.set", jsonOf("session_id" to liveId, "key" to "model", "value" to value, "confirm_expensive_model" to true))
        return r
    }

    suspend fun setReasoning(liveId: String, level: String) {
        socket.call("config.set", jsonOf("session_id" to liveId, "key" to "reasoning", "value" to level))
    }

    suspend fun setTitle(liveId: String, title: String) {
        socket.call("session.title", jsonOf("session_id" to liveId, "title" to title))
        sessions[liveId]?.update { it.copy(title = title) }
    }

    suspend fun steer(liveId: String, text: String) { socket.call("session.steer", jsonOf("session_id" to liveId, "text" to text)) }

    suspend fun close(liveId: String) {
        runCatching { socket.call("session.close", jsonOf("session_id" to liveId)) }
        sessions.remove(liveId)?.value?.storedId?.let { storedToLive.remove(it) }
        syncRoster()
    }

    fun forget(liveId: String) { sessions.remove(liveId)?.value?.storedId?.let { storedToLive.remove(it) }; syncRoster() }

    // ---- reconnect ----
    private suspend fun onReconnected() {
        val open = sessions.values.map { it.value }.filter { it.storedId != null }
        for (s in open) {
            try {
                val r = socket.call("session.resume", jsonOf("session_id" to s.storedId, "cols" to 100, "profile" to s.profile.takeIf { it != "default" }))
                val newLive = r.str("session_id") ?: continue
                if (newLive != s.liveId) {
                    val flow = sessions.remove(s.liveId) ?: continue
                    flow.update { it.copy(liveId = newLive) }
                    sessions[newLive] = flow
                    syncRoster()
                }
                storedToLive[s.storedId!!] = newLive
                sessions[newLive]?.update { it.copy(status = r.str("status") ?: "idle", error = null) }
            } catch (e: Exception) {
                Log.w("ChatRepo", "re-resume failed for ${s.storedId}: ${e.message}")
            }
        }
    }

    // ---- event routing ----
    private fun onEvent(ev: GatewayEvent) {
        val t = ev.type
        if (t.endsWith(".changed")) { _changes.tryEmit(t); return }
        if (t == "gateway.ready" || t == "skin.changed") return
        var live = ev.sessionId.takeIf { it.isNotBlank() }
        if (live == null) {
            if (t.startsWith("subagent.")) return  // never attribute unscoped subagent events
            live = focusedLive ?: return
        }
        val st = sessions[live] ?: return
        if (t == "message.start") focusedLive = live
        handle(st, ev)
    }

    private fun handle(st: MutableStateFlow<ChatSessionState>, ev: GatewayEvent) {
        val p = ev.payload
        when (ev.type) {
            "message.start" -> st.update { it.copy(status = "streaming", statusLine = null, items = it.items + ChatItem.Assistant(newId(), "", model = it.model, at = System.currentTimeMillis())) }
            "message.delta" -> {
                val text = p.str("text") ?: return
                st.update { s -> s.copy(status = "streaming", items = s.items.appendToStreaming { a -> a.copy(text = a.text + text) }) }
            }
            "reasoning.delta", "reasoning.available" -> {
                val text = p.str("text") ?: return
                st.update { s -> s.copy(items = s.items.appendToStreaming { a -> a.copy(reasoning = if (ev.type == "reasoning.available") text else a.reasoning + text) }) }
            }
            "thinking.delta" -> st.update { it.copy(statusLine = p.str("text")) }
            "message.interim" -> {
                val text = p.str("text") ?: return
                val already = p.bool("already_streamed") ?: false
                st.update { s ->
                    val items = if (already) s.items.appendToStreaming { a -> a.copy(interim = true, streaming = false, status = "complete") }
                    else s.items.appendToStreaming { a -> a.copy(text = text, interim = true, streaming = false, status = "complete") }
                    // open a new streaming bubble for what follows
                    s.copy(items = items + ChatItem.Assistant(newId(), "", model = s.model))
                }
            }
            "message.complete" -> {
                val text = p.str("text") ?: ""
                val status = p.str("status") ?: "complete"
                val err = p.str("error")
                val partial = p.bool("partial") ?: false
                st.update { s ->
                    var items = s.items
                    val idx = items.indexOfLast { it is ChatItem.Assistant && it.streaming }
                    items = if (idx >= 0) {
                        val a = items[idx] as ChatItem.Assistant
                        val finalText = if (status == "error" && !partial && text.isBlank()) a.text else if (text.isNotBlank()) text else a.text
                        items.toMutableList().also { it[idx] = a.copy(text = finalText, streaming = false, status = status, error = err, usage = p.child("usage"), reasoning = p.str("reasoning") ?: a.reasoning) }
                    } else if (text.isNotBlank() || err != null) {
                        items + ChatItem.Assistant(newId(), text, streaming = false, status = status, error = err, usage = p.child("usage"), model = s.model)
                    } else items
                    // drop empty trailing assistant bubbles
                    items = items.filterNot { it is ChatItem.Assistant && it.text.isBlank() && it.reasoning.isBlank() && !it.streaming }
                    s.copy(items = items, status = "idle", statusLine = null, usage = p.child("usage") ?: s.usage, error = if (status == "error") err else null)
                }
                val s = st.value
                _completions.tryEmit(CompletionNotice(s.liveId, s.storedId, s.profile, s.title, text.take(160), status))
            }
            "error" -> {
                val msg = p.str("message") ?: "Unknown error"
                st.update { it.copy(error = msg, status = "idle", items = it.items.appendToStreaming { a -> a.copy(streaming = false, status = "error", error = msg) }) }
                val s = st.value
                _completions.tryEmit(CompletionNotice(s.liveId, s.storedId, s.profile, s.title, msg.take(160), "error"))
            }
            "tool.generating" -> st.update { it.copy(statusLine = "Preparing ${p.str("name") ?: "tool"}…", status = "working") }
            "tool.start" -> {
                val toolId = p.str("tool_id") ?: newId()
                st.update { s ->
                    s.copy(status = "working", statusLine = null, items = s.items + ChatItem.Tool(newId(), toolId, p.str("name") ?: "tool", p.str("context") ?: "", args = p.str("args_text") ?: p.child("args")?.prettyText()))
                }
            }
            "tool.complete" -> {
                val toolId = p.str("tool_id")
                st.update { s ->
                    val idx = s.items.indexOfLast { it is ChatItem.Tool && (toolId == null || it.toolId == toolId) && !it.done }
                    val items = if (idx >= 0) s.items.toMutableList().also { l ->
                        val t = l[idx] as ChatItem.Tool
                        l[idx] = t.copy(done = true, result = p.str("result_text") ?: p.child("result")?.prettyText(), summary = p.str("summary"), durationS = p.dbl("duration_s"), args = t.args ?: p.child("args")?.prettyText())
                    } else s.items + ChatItem.Tool(newId(), toolId ?: newId(), p.str("name") ?: "tool", "", result = p.child("result")?.prettyText(), done = true)
                    s.copy(items = items, status = if (s.status == "idle") "idle" else "working")
                }
            }
            "approval.request" -> st.update {
                it.copy(status = "waiting", approval = PendingApproval(
                    command = p.str("command") ?: "", description = p.str("description") ?: "",
                    choices = p.list("choices").mapNotNull { c -> c.asString() }.ifEmpty { listOf("once", "session", "always", "deny") },
                    patternKey = p.str("pattern_key"),
                ))
            }
            "clarify.request" -> st.update {
                it.copy(status = "waiting", clarify = PendingClarify(p.str("request_id") ?: "", p.str("question") ?: "", p.list("choices").mapNotNull { c -> c.asString() }, p.bool("multi_select") ?: false))
            }
            "clarify.expire" -> st.update { it.copy(clarify = null, status = "working") }
            "sudo.request" -> st.update { it.copy(status = "waiting", secret = PendingSecret(p.str("request_id") ?: "", "sudo", "The agent needs your sudo password")) }
            "secret.request" -> st.update { it.copy(status = "waiting", secret = PendingSecret(p.str("request_id") ?: "", "secret", p.str("prompt") ?: p.str("name") ?: "The agent needs a secret value")) }
            "sudo.expire", "secret.expire" -> st.update { it.copy(secret = null, status = "working") }
            "status.update" -> {
                val kind = p.str("kind"); val text = p.str("text") ?: ""
                st.update { s ->
                    when (kind) {
                        "ready" -> s.copy(status = if (s.status == "starting") "idle" else s.status, statusLine = null)
                        "lifecycle", "process", "warn", "compacting", "compressing", "compacted" -> s.copy(items = if (kind == "warn" || kind == "compacted") s.items + ChatItem.System(newId(), text, kind) else s.items, statusLine = text.takeIf { kind != "compacted" })
                        else -> s.copy(statusLine = text)
                    }
                }
            }
            "notification.show" -> st.update { it.copy(notice = p.str("text")) }
            "notification.clear" -> st.update { it.copy(notice = null) }
            "review.summary" -> st.update { it.copy(items = it.items + ChatItem.System(newId(), p.str("text") ?: "", "review")) }
            "background.complete" -> st.update { it.copy(items = it.items + ChatItem.System(newId(), p.str("text") ?: "", "background")) }
            "session.info" -> applyInfo(st.value.liveId, p)
            "session.title" -> st.update { it.copy(title = p.str("title") ?: it.title) }
            "moa.phase", "moa.progress", "moa.aggregating", "moa.reference" -> st.update { it.copy(statusLine = "MoA: " + (p.str("phase") ?: p.str("label") ?: p.str("aggregator") ?: "working")) }
            else -> if (ev.type.startsWith("subagent.")) handleSubagent(st, ev)
        }
    }

    private fun handleSubagent(st: MutableStateFlow<ChatSessionState>, ev: GatewayEvent) {
        val p = ev.payload
        val sid = p.str("subagent_id") ?: p.str("child_session_id") ?: (p.str("goal") ?: "sub")
        val phase = ev.type.removePrefix("subagent.")
        st.update { s ->
            val idx = s.items.indexOfLast { it is ChatItem.Delegation && it.subagentId == sid }
            val existing = if (idx >= 0) s.items[idx] as ChatItem.Delegation else null
            val updated = (existing ?: ChatItem.Delegation(newId(), sid, p.str("goal") ?: "Delegated task", "started")).let { d ->
                when (phase) {
                    "spawn_requested", "start" -> d.copy(status = "running", model = p.str("model") ?: d.model, childSessionId = p.str("child_session_id") ?: d.childSessionId)
                    "thinking" -> d.copy(status = "thinking")
                    "tool" -> d.copy(status = "running", toolCount = d.toolCount + 1, lastText = p.str("tool_preview") ?: p.str("tool_name") ?: d.lastText)
                    "progress" -> d.copy(lastText = p.str("text") ?: d.lastText)
                    "complete" -> d.copy(status = p.str("status") ?: "complete", summary = p.str("summary") ?: p.str("text"), toolCount = p.int("tool_count") ?: d.toolCount)
                    else -> d
                }
            }
            val items = if (idx >= 0) s.items.toMutableList().also { it[idx] = updated } else s.items + updated
            s.copy(items = items)
        }
    }

    private fun applyInfo(liveId: String, info: JsonElement?) {
        if (info == null) return
        sessions[liveId]?.update {
            it.copy(
                model = info.str("model") ?: it.model,
                provider = info.str("provider") ?: it.provider,
                reasoningEffort = info.str("reasoning_effort") ?: it.reasoningEffort,
                approvalMode = info.str("approval_mode") ?: it.approvalMode,
                title = info.str("title")?.takeIf { t -> t.isNotBlank() } ?: it.title,
                storedId = info.str("stored_session_id") ?: it.storedId,
                profile = info.str("profile_name")?.takeIf { n -> n.isNotBlank() } ?: it.profile,
                usage = info.child("usage") ?: it.usage,
                status = if (info.bool("running") == true && it.status == "idle") "working" else if (info.bool("running") == false && it.status == "starting") "idle" else it.status,
                loading = false,
            )
        }
        info.str("stored_session_id")?.let { storedToLive[it] = liveId }
    }

    private fun List<ChatItem>.appendToStreaming(f: (ChatItem.Assistant) -> ChatItem.Assistant): List<ChatItem> {
        val idx = indexOfLast { it is ChatItem.Assistant && it.streaming }
        return if (idx >= 0) toMutableList().also { it[idx] = f(it[idx] as ChatItem.Assistant) }
        else this + f(ChatItem.Assistant(newId(), ""))
    }

    private fun newId() = UUID.randomUUID().toString()

    companion object {
        /** Hermes' registry title for a profile's canonical conversation. */
        const val MAIN_CHAT_TITLE = "Bot Chat"
    }
}
