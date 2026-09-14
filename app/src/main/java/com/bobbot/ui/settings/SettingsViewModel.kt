package com.bobbot.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobbot.core.auth.AuthManager
import com.bobbot.core.net.HermesClient
import com.bobbot.core.net.str
import com.bobbot.data.prefs.AppPrefs
import com.bobbot.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import kotlin.random.Random

data class SettingsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    // connection
    val baseUrl: String = "",
    val displayName: String = "",
    val email: String = "",
    val serverVersion: String = "",
    val gatewayState: String = "",
    val gatewayOk: Boolean? = null,
    val testing: Boolean = false,
    val testResult: String? = null,
    val testFailed: Boolean = false,
    // notifications
    val notificationsEnabled: Boolean = true,
    val watchBoard: Boolean = true,
    val watchCron: Boolean = true,
    val watchRelay: Boolean = true,
    // ntfy
    val ntfyServer: String = "https://ntfy.sh",
    val ntfyTopic: String = "",
    val ntfyToken: String = "",
    val ntfyBusy: Boolean = false,
    val ntfyMessage: String? = null,
    val ntfyError: String? = null,
    // app
    val appVersion: String = "",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPrefs,
    private val system: SystemRepository,
    private val auth: AuthManager,
    private val client: HermesClient,
    private val okHttp: OkHttpClient,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(appVersion = com.bobbot.BuildConfig.VERSION_NAME))
    val state: StateFlow<SettingsUiState> = _state

    /** ntfy fields are editable, so they are seeded from prefs exactly once. */
    private var ntfySeeded = false

    init {
        viewModelScope.launch {
            prefs.connection.collect { c ->
                _state.update { it.copy(baseUrl = c.baseUrl, displayName = it.displayName.ifBlank { c.displayName }) }
            }
        }
        viewModelScope.launch {
            prefs.notifications.collect { n ->
                _state.update { s ->
                    s.copy(
                        notificationsEnabled = n.enabled,
                        watchBoard = n.watchBoard,
                        watchCron = n.watchCron,
                        watchRelay = n.watchRelay,
                        ntfyServer = if (ntfySeeded) s.ntfyServer else n.ntfyServer,
                        ntfyTopic = if (ntfySeeded) s.ntfyTopic else n.ntfyTopic,
                        ntfyToken = if (ntfySeeded) s.ntfyToken else n.ntfyToken,
                    )
                }
                ntfySeeded = true
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val st = system.refreshStatus()
                _state.update {
                    it.copy(serverVersion = st.version, gatewayState = st.gatewayState, gatewayOk = st.gatewayRunning)
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Could not reach the server") }
            }
            val me = system.me()
            if (me != null) {
                _state.update {
                    it.copy(
                        displayName = me.str("display_name") ?: me.str("name") ?: it.displayName,
                        email = me.str("email") ?: it.email,
                    )
                }
            }
            _state.update { it.copy(loading = false) }
        }
    }

    // ---- connection ----

    fun saveBaseUrl(raw: String) {
        viewModelScope.launch {
            val normalized = client.normalizeBaseUrl(raw)
            if (normalized == null) {
                _state.update { it.copy(error = "That doesn't look like a server address") }
                return@launch
            }
            prefs.setBaseUrl(normalized)
            _state.update { it.copy(baseUrl = normalized, error = null) }
            refresh()
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _state.update { it.copy(testing = true, testResult = null, testFailed = false) }
            try {
                val st = system.refreshStatus()
                _state.update {
                    it.copy(
                        testing = false,
                        testFailed = false,
                        testResult = "Reached Hermes ${st.version} · gateway ${st.gatewayState}",
                        serverVersion = st.version,
                        gatewayState = st.gatewayState,
                        gatewayOk = st.gatewayRunning,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(testing = false, testFailed = true, testResult = e.message ?: "Connection failed") }
            }
        }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { auth.signOut() }
            runCatching { prefs.setSetupComplete(false) }
            onDone()
        }
    }

    // ---- notifications ----

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setNotificationsEnabled(enabled) }
    }

    fun setWatch(board: Boolean? = null, cron: Boolean? = null, relay: Boolean? = null) {
        viewModelScope.launch { prefs.setWatch(board = board, cron = cron, relay = relay) }
    }

    // ---- ntfy ----

    fun onNtfyServerChange(v: String) = _state.update { it.copy(ntfyServer = v, ntfyMessage = null, ntfyError = null) }
    fun onNtfyTopicChange(v: String) = _state.update { it.copy(ntfyTopic = v, ntfyMessage = null, ntfyError = null) }
    fun onNtfyTokenChange(v: String) = _state.update { it.copy(ntfyToken = v, ntfyMessage = null, ntfyError = null) }

    fun generateTopic() {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val suffix = buildString { repeat(12) { append(alphabet[Random.nextInt(alphabet.length)]) } }
        _state.update { it.copy(ntfyTopic = "bobbot-$suffix", ntfyMessage = null, ntfyError = null) }
    }

    fun saveNtfy() {
        viewModelScope.launch {
            val s = _state.value
            val server = s.ntfyServer.trim().trimEnd('/').ifBlank { "https://ntfy.sh" }
            val topic = s.ntfyTopic.trim()
            val token = s.ntfyToken.trim()
            if (topic.isBlank()) {
                _state.update { it.copy(ntfyError = "Pick a topic first — tap Generate.") }
                return@launch
            }
            _state.update { it.copy(ntfyBusy = true, ntfyMessage = null, ntfyError = null) }
            prefs.setNtfy(server, topic, token)
            try {
                system.enableNtfy(server, topic, token)
                _state.update {
                    it.copy(ntfyBusy = false, ntfyServer = server, ntfyTopic = topic, ntfyMessage = "Hermes will deliver to $topic")
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        ntfyBusy = false,
                        ntfyServer = server,
                        ntfyTopic = topic,
                        ntfyError = "Saved on this phone, but Hermes rejected it: ${e.message}",
                    )
                }
            }
        }
    }

    /** Posts straight to ntfy so the phone buzzes without involving Hermes. */
    fun sendTestPush() {
        viewModelScope.launch {
            val s = _state.value
            val server = s.ntfyServer.trim().trimEnd('/').ifBlank { "https://ntfy.sh" }
            val topic = s.ntfyTopic.trim()
            if (topic.isBlank()) {
                _state.update { it.copy(ntfyError = "Pick a topic first — tap Generate.") }
                return@launch
            }
            _state.update { it.copy(ntfyBusy = true, ntfyMessage = null, ntfyError = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val builder = Request.Builder()
                        .url("$server/$topic")
                        .header("Title", "BobBot")
                        .post("BobBot test notification".toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    if (s.ntfyToken.isNotBlank()) builder.header("Authorization", "Bearer ${s.ntfyToken.trim()}")
                    okHttp.newCall(builder.build()).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("ntfy returned ${r.code}")
                    }
                }
            }
            result.fold(
                onSuccess = { _state.update { it.copy(ntfyBusy = false, ntfyMessage = "Test sent — your phone should buzz.") } },
                onFailure = { e -> _state.update { it.copy(ntfyBusy = false, ntfyError = e.message ?: "Test push failed") } },
            )
        }
    }
}
