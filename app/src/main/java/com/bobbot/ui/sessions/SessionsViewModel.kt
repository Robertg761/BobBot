package com.bobbot.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobbot.core.net.HermesApi
import com.bobbot.core.net.SocketState
import com.bobbot.core.net.list
import com.bobbot.data.model.Bot
import com.bobbot.data.model.SessionSummary
import com.bobbot.data.repo.BotsRepository
import com.bobbot.data.repo.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionsUi(
    val loading: Boolean = false,
    val error: String? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val bots: List<Bot> = emptyList(),
    val filter: String? = null,
    val searching: Boolean = false,
    val query: String = "",
    val searchResults: List<SessionSummary>? = null,
    val connected: Boolean? = null,
) {
    val filtered: List<SessionSummary>
        get() = (searchResults ?: sessions).filter { filter == null || it.profile == filter }
            .sortedWith(compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.lastActive })
}

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val api: HermesApi,
    private val bots: BotsRepository,
    private val chat: ChatRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(SessionsUi())
    val ui: StateFlow<SessionsUi> = _ui
    private var searchJob: Job? = null

    init {
        viewModelScope.launch { chat.socketState.collect { s -> _ui.update { it.copy(connected = when (s) { SocketState.CONNECTED -> true; SocketState.CONNECTING -> null; else -> false }) } } }
        viewModelScope.launch { chat.changes.collect { if (it == "sessions.changed") load(quiet = true) } }
        viewModelScope.launch { runCatching { chat.connect() } }
    }

    fun load(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) _ui.update { it.copy(loading = true, error = null) }
            try {
                val botList = runCatching { bots.refresh() }.getOrDefault(bots.bots.value)
                val all = mutableListOf<SessionSummary>()
                val names = botList.map { it.name }.ifEmpty { listOf("default") }
                for (n in names) {
                    runCatching { api.sessions(n.takeIf { it != "default" }, limit = 40) }.getOrNull()?.let { list ->
                        all += list.map { it.copy(profile = n) }
                    }
                }
                _ui.update { it.copy(loading = false, sessions = all, bots = botList) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun setFilter(name: String?) = _ui.update { it.copy(filter = name) }
    fun toggleSearch() = _ui.update { it.copy(searching = !it.searching, query = "", searchResults = null) }

    fun setQuery(q: String) {
        _ui.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.isBlank()) { _ui.update { it.copy(searchResults = null) }; return }
        searchJob = viewModelScope.launch {
            delay(300)
            val results = mutableListOf<SessionSummary>()
            for (n in _ui.value.bots.map { it.name }.ifEmpty { listOf("default") }) {
                runCatching { api.searchSessions(n.takeIf { it != "default" }, q) }.getOrNull()?.let { r ->
                    results += (r.list("results").ifEmpty { r.list("sessions") }).map { SessionSummary.from(it).copy(profile = n) }
                }
            }
            _ui.update { it.copy(searchResults = results) }
        }
    }

    fun rename(s: SessionSummary, title: String) = mutate { api.patchSession(s.profile.takeIf { it != "default" }, s.id, title = title) }
    fun pin(s: SessionSummary, pinned: Boolean) = mutate { api.patchSession(s.profile.takeIf { it != "default" }, s.id, pinned = pinned) }
    fun archive(s: SessionSummary) = mutate { api.patchSession(s.profile.takeIf { it != "default" }, s.id, archived = true) }
    fun delete(s: SessionSummary) = mutate { api.deleteSession(s.profile.takeIf { it != "default" }, s.id) }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); load(quiet = true) } catch (e: Exception) { _ui.update { it.copy(error = e.message) } }
        }
    }
}
