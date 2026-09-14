package com.bobbot.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobbot.core.auth.AuthManager
import com.bobbot.core.auth.AuthState
import com.bobbot.core.net.HermesApi
import com.bobbot.core.net.HermesClient
import com.bobbot.core.net.asString
import com.bobbot.core.net.bool
import com.bobbot.core.net.list
import com.bobbot.core.net.str
import com.bobbot.data.model.Bot
import com.bobbot.data.prefs.AppPrefs
import com.bobbot.data.repo.BotsRepository
import com.bobbot.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.inject.Inject

enum class SetupStep { Welcome, Server, SignIn, Notifications, Ready }

data class SetupUi(
    val step: SetupStep = SetupStep.Welcome,
    val url: String = "",
    val checking: Boolean = false,
    val serverOk: Boolean? = null,
    val serverVersion: String = "",
    val authRequired: Boolean = true,
    val nativeFlow: Boolean = true,
    val error: String? = null,
    val auth: AuthState = AuthState.Idle,
    val sessionToken: String = "",
    val bots: List<Bot> = emptyList(),
    val notificationsOn: Boolean = true,
    val ntfyTopic: String = "",
    val ntfyServer: String = "https://ntfy.sh",
    val ntfyBusy: Boolean = false,
    val ntfyResult: String? = null,
    val displayName: String = "",
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val prefs: AppPrefs,
    private val client: HermesClient,
    private val api: HermesApi,
    private val auth: AuthManager,
    private val bots: BotsRepository,
    private val system: SystemRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(SetupUi())
    val ui: StateFlow<SetupUi> = _ui

    init {
        viewModelScope.launch {
            val c = prefs.current()
            val n = prefs.currentNotifications()
            _ui.update { it.copy(url = c.baseUrl, ntfyTopic = n.ntfyTopic, ntfyServer = n.ntfyServer) }
        }
        viewModelScope.launch { auth.state.collect { s -> onAuth(s) } }
    }

    fun go(step: SetupStep) = _ui.update { it.copy(step = step, error = null) }
    fun setUrl(v: String) = _ui.update { it.copy(url = v, serverOk = null, error = null) }
    fun setSessionToken(v: String) = _ui.update { it.copy(sessionToken = v) }
    fun setNotificationsOn(v: Boolean) = _ui.update { it.copy(notificationsOn = v) }
    fun setNtfyTopic(v: String) = _ui.update { it.copy(ntfyTopic = v) }
    fun setNtfyServer(v: String) = _ui.update { it.copy(ntfyServer = v) }

    fun generateTopic() {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val r = SecureRandom()
        val t = "bobbot-" + (1..14).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
        _ui.update { it.copy(ntfyTopic = t) }
    }

    fun checkServer() {
        val normalized = HermesClient.normalizeBaseUrl(_ui.value.url)
        if (normalized == null) { _ui.update { it.copy(error = "Enter a valid address like 192.168.1.20:9119") }; return }
        viewModelScope.launch {
            _ui.update { it.copy(checking = true, error = null) }
            try {
                prefs.setBaseUrl(normalized)
                val h = api.health()
                val ok = h.bool("ok") ?: false
                val st = runCatching { api.statusRaw() }.getOrNull()
                val flows: List<String> = st.list("auth_flows").mapNotNull { it.asString() }
                _ui.update {
                    it.copy(
                        checking = false, serverOk = ok, url = normalized,
                        serverVersion = h.str("version") ?: "",
                        authRequired = h.bool("auth_required") ?: true,
                        nativeFlow = flows.contains("native_pkce") || flows.isEmpty(),
                    )
                }
                if (ok) {
                    if (h.bool("auth_required") == false) {
                        // Loopback-bound server: no OAuth, just needs the session token.
                        go(SetupStep.SignIn)
                    } else go(SetupStep.SignIn)
                }
            } catch (e: Exception) {
                _ui.update { it.copy(checking = false, serverOk = false, error = friendly(e)) }
            }
        }
    }

    /** Returns the authorize URL to open in a Custom Tab. */
    fun beginSignIn(onUrl: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val url = auth.beginLogin()
                onUrl(url)
            } catch (e: Exception) {
                _ui.update { it.copy(error = friendly(e)) }
            }
        }
    }

    fun useSessionToken() {
        viewModelScope.launch {
            _ui.update { it.copy(checking = true, error = null) }
            val ok = auth.useSessionToken(_ui.value.sessionToken)
            _ui.update { it.copy(checking = false, error = if (ok) null else "That token was rejected by the server") }
            if (ok) afterSignIn()
        }
    }

    private fun onAuth(s: AuthState) {
        _ui.update { it.copy(auth = s) }
        if (s is AuthState.SignedIn) afterSignIn(s.displayName)
        if (s is AuthState.Failed) _ui.update { it.copy(error = s.message) }
    }

    private fun afterSignIn(name: String = "") {
        viewModelScope.launch {
            val list = runCatching { bots.refresh() }.getOrDefault(emptyList())
            _ui.update { it.copy(bots = list, displayName = name, step = SetupStep.Notifications, error = null) }
        }
    }

    fun cancelSignIn() = auth.cancel()

    fun saveNotifications(onDone: () -> Unit) {
        viewModelScope.launch {
            val u = _ui.value
            prefs.setNotificationsEnabled(u.notificationsOn)
            if (u.notificationsOn && u.ntfyTopic.isNotBlank()) {
                _ui.update { it.copy(ntfyBusy = true, ntfyResult = null) }
                prefs.setNtfy(u.ntfyServer, u.ntfyTopic, "")
                val res = runCatching { system.enableNtfy(u.ntfyServer, u.ntfyTopic, null) }
                _ui.update { it.copy(ntfyBusy = false, ntfyResult = res.fold({ "Hermes will push bot messages to this phone." }, { "Saved locally, but Hermes could not enable ntfy: ${friendly(it)}" })) }
            }
            _ui.update { it.copy(step = SetupStep.Ready) }
            onDone()
        }
    }

    private fun friendly(e: Throwable): String = when {
        e is com.bobbot.core.net.HermesHttpException && e.isHostMismatch -> "The server rejected this hostname. Use the same address the dashboard was started with."
        e is com.bobbot.core.net.HermesHttpException -> "Server error ${e.code}: ${e.reason ?: e.body.take(120)}"
        e is java.net.ConnectException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException -> "Could not reach the server. Check the address, that the dashboard is running on port 9119, and that you are on the same network or VPN."
        else -> e.message ?: "Something went wrong"
    }
}
