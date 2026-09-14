package com.bobbot.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobbot.core.net.HermesApi
import com.bobbot.core.net.SocketState
import com.bobbot.core.net.str
import com.bobbot.data.model.Bot
import com.bobbot.data.repo.BotsRepository
import com.bobbot.data.repo.ChatRepository
import com.bobbot.data.repo.ChatSessionState
import com.bobbot.data.repo.ModelCatalog
import com.bobbot.data.repo.ModelsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ChatUi(
    val liveId: String? = null,
    val session: ChatSessionState? = null,
    val bot: Bot? = null,
    val connecting: Boolean = true,
    val error: String? = null,
    val input: String = "",
    val catalog: ModelCatalog? = null,
    val showModelPicker: Boolean = false,
    val showReasoningPicker: Boolean = false,
    val toast: String? = null,
    val socket: SocketState = SocketState.DISCONNECTED,
    val attaching: Boolean = false,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val chat: ChatRepository,
    private val bots: BotsRepository,
    private val models: ModelsRepository,
    private val api: HermesApi,
) : ViewModel() {
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui
    private var stateJob: Job? = null
    private var profile: String = "default"
    private var mainConversation = false
    private var opening = false

    init {
        viewModelScope.launch { chat.socketState.collect { s -> _ui.update { it.copy(socket = s) } } }
    }

    fun open(sessionId: String?, profile: String, mainConversation: Boolean = false) {
        if (_ui.value.liveId != null || opening) return
        opening = true
        this.mainConversation = mainConversation
        this.profile = profile
        viewModelScope.launch {
            _ui.update { it.copy(connecting = true, error = null) }
            try {
                val bot = bots.cached(profile) ?: runCatching { bots.refresh() }.getOrNull()?.firstOrNull { it.name == profile }
                _ui.update { it.copy(bot = bot) }
                val live = if (mainConversation && sessionId == null) chat.openMainConversation(profile) else if (sessionId == null) chat.createSession(profile) else {
                    val target = runCatching { api.latestDescendant(profile.takeIf { it != "default" }, sessionId) }.getOrDefault(sessionId)
                    chat.resumeSession(target, profile)
                }
                _ui.update { it.copy(liveId = live, connecting = false) }
                stateJob?.cancel()
                stateJob = viewModelScope.launch { chat.state(live)?.collect { s -> _ui.update { it.copy(session = s, liveId = s.liveId) } } }
            } catch (e: Exception) {
                _ui.update { it.copy(connecting = false, error = e.message ?: "Could not open chat") }
            } finally {
                opening = false
            }
        }
    }

    fun retry(sessionId: String?) { _ui.update { it.copy(liveId = null) }; open(sessionId, profile, mainConversation) }

    fun setInput(v: String) = _ui.update { it.copy(input = v) }

    fun send() {
        val text = _ui.value.input.trim()
        val live = _ui.value.liveId ?: return
        if (text.isEmpty() && _ui.value.session?.attachments.isNullOrEmpty()) return
        _ui.update { it.copy(input = "") }
        viewModelScope.launch {
            try { chat.send(live, text.ifEmpty { "(see attached image)" }) } catch (e: Exception) { toast(e.message ?: "Send failed") }
        }
    }

    fun interrupt() { val live = _ui.value.liveId ?: return; viewModelScope.launch { chat.interrupt(live) } }

    fun approve(choice: String) { val live = _ui.value.liveId ?: return; viewModelScope.launch { runCatching { chat.respondApproval(live, choice) }.onFailure { toast(it.message ?: "failed") } } }
    fun clarify(requestId: String, answer: String) { val live = _ui.value.liveId ?: return; viewModelScope.launch { runCatching { chat.respondClarify(live, requestId, answer) }.onFailure { toast(it.message ?: "failed") } } }
    fun secret(requestId: String, kind: String, value: String) { val live = _ui.value.liveId ?: return; viewModelScope.launch { runCatching { chat.respondSecret(live, requestId, kind, value) }.onFailure { toast(it.message ?: "failed") } } }

    fun attach(uri: Uri) {
        val live = _ui.value.liveId ?: return
        viewModelScope.launch {
            _ui.update { it.copy(attaching = true) }
            try {
                val (b64, name) = withContext(Dispatchers.IO) {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Could not read image")
                    if (bytes.size > 24 * 1024 * 1024) throw IllegalStateException("Image is larger than 24 MB")
                    val type = ctx.contentResolver.getType(uri) ?: "image/png"
                    val ext = when { type.contains("jpeg") || type.contains("jpg") -> "jpg"; type.contains("webp") -> "webp"; type.contains("gif") -> "gif"; else -> "png" }
                    Base64.encodeToString(bytes, Base64.NO_WRAP) to "photo_${System.currentTimeMillis()}.$ext"
                }
                chat.attachImage(live, b64, name)
            } catch (e: Exception) { toast(e.message ?: "Attach failed") }
            _ui.update { it.copy(attaching = false) }
        }
    }

    fun openModelPicker() {
        _ui.update { it.copy(showModelPicker = true) }
        if (_ui.value.catalog == null) viewModelScope.launch {
            runCatching { models.refresh(profile) }.onSuccess { c -> _ui.update { it.copy(catalog = c) } }.onFailure { toast(it.message ?: "Could not load models") }
        }
    }
    fun closeModelPicker() = _ui.update { it.copy(showModelPicker = false) }

    /** Switch the model for this chat only (scope=session); optionally make it the bot's default. */
    fun pickModel(provider: String, model: String, makeDefault: Boolean = false) {
        val live = _ui.value.liveId ?: return
        _ui.update { it.copy(showModelPicker = false) }
        viewModelScope.launch {
            try {
                val r = chat.setModel(live, "$model --provider $provider", global = makeDefault)
                val warn = r.str("warning")
                toast(if (makeDefault) "Default model for ${profile} is now $model" else "This chat now uses $model" + (warn?.let { " · $it" } ?: ""))
            } catch (e: Exception) {
                toast(if (e.message?.contains("4009") == true || e.message?.contains("busy", true) == true) "Wait for the bot to finish, then switch" else e.message ?: "Switch failed")
            }
        }
    }

    fun openReasoningPicker() = _ui.update { it.copy(showReasoningPicker = true) }
    fun closeReasoningPicker() = _ui.update { it.copy(showReasoningPicker = false) }
    fun pickReasoning(level: String) {
        val live = _ui.value.liveId ?: return
        _ui.update { it.copy(showReasoningPicker = false) }
        viewModelScope.launch { runCatching { chat.setReasoning(live, level) }.onFailure { toast(it.message ?: "failed") }.onSuccess { toast("Reasoning: $level") } }
    }

    fun rename(title: String) { val live = _ui.value.liveId ?: return; viewModelScope.launch { runCatching { chat.setTitle(live, title) } } }

    fun toast(msg: String) { _ui.update { it.copy(toast = msg) } }
    fun clearToast() = _ui.update { it.copy(toast = null) }
}
