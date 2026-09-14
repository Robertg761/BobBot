package com.bobbot.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobbot.core.net.HermesApi
import com.bobbot.core.net.SocketState
import com.bobbot.core.net.list
import com.bobbot.data.model.Bot
import com.bobbot.data.model.SessionSummary
import com.bobbot.data.prefs.AppPrefs
import com.bobbot.data.repo.BotNames
import com.bobbot.data.repo.BotsRepository
import com.bobbot.data.repo.ChatRepository
import com.bobbot.data.repo.ChatSessionState
import com.bobbot.data.repo.GroupRoom
import com.bobbot.data.repo.GroupsRepository
import com.bobbot.data.repo.RosterEntry
import com.bobbot.data.repo.RosterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InboxUi(
    val loading: Boolean = true,
    val error: String? = null,
    val rows: List<InboxRow> = emptyList(),
    val bots: List<Bot> = emptyList(),
    val connected: Boolean? = null,
    val groupsSupported: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val searchResults: List<SessionSummary>? = null,
) {
    /** Rows filtered by the search box; a blank query shows everything. */
    val visible: List<InboxRow>
        get() = if (query.isBlank()) rows else rows.filter {
            it.title.contains(query, ignoreCase = true) || it.preview.contains(query, ignoreCase = true)
        }
}

private data class Inputs(
    val bots: List<Bot>,
    val roster: List<RosterEntry>,
    val rooms: List<GroupRoom>,
    val live: List<ChatSessionState>,
    val seen: Map<String, Long>,
)

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val api: HermesApi,
    private val bots: BotsRepository,
    private val chat: ChatRepository,
    private val roster: RosterRepository,
    private val groups: GroupsRepository,
    private val prefs: AppPrefs,
) : ViewModel() {
    private val _ui = MutableStateFlow(InboxUi())
    val ui: StateFlow<InboxUi> = _ui

    private val rosterRows = MutableStateFlow<List<RosterEntry>>(emptyList())
    private val rooms = MutableStateFlow<List<GroupRoom>>(emptyList())
    private var searchJob: Job? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            combine(bots.bots, rosterRows, rooms, chat.liveStates, prefs.seen) { b, r, rm, l, s -> Inputs(b, r, rm, l, s) }
                .combine(BotNames.names) { i, names ->
                    buildInbox(
                        bots = i.bots,
                        chats = i.roster.mapNotNull { e -> e.chat?.let { e.profile to it } }.toMap(),
                        live = i.live,
                        working = i.roster.filter { it.workerBusy() }.map { it.profile }.toSet(),
                        rooms = i.rooms,
                        seen = i.seen,
                        nameOf = { p -> names[p]?.takeIf { it.isNotBlank() } ?: BotNames.fallback(p) },
                    )
                }
                .collect { rows -> _ui.update { it.copy(rows = rows, bots = bots.bots.value) } }
        }
        viewModelScope.launch {
            chat.socketState.collect { s ->
                _ui.update { it.copy(connected = when (s) { SocketState.CONNECTED -> true; SocketState.CONNECTING -> null; else -> false }) }
            }
        }
        viewModelScope.launch { chat.changes.collect { if (it == "sessions.changed") load(quiet = true) } }
        viewModelScope.launch { chat.completions.collect { load(quiet = true) } }
        viewModelScope.launch { runCatching { chat.connect() } }
        // Activity from the desktop, cron delivery and worker heartbeats only show up by asking again.
        viewModelScope.launch {
            while (true) {
                delay(20_000)
                load(quiet = true)
            }
        }
    }

    fun load(quiet: Boolean = false) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            if (!quiet) _ui.update { it.copy(loading = it.rows.isEmpty(), error = null) }
            try {
                val botList = bots.refresh()
                rosterRows.value = roster.roster()
                _ui.update { it.copy(loading = false, error = null, bots = botList) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _ui.update { it.copy(loading = false, error = e.message ?: "Could not load your bots") }
            }
            refreshRooms()
        }
    }

    private suspend fun refreshRooms() {
        try {
            val ok = groups.supported()
            _ui.update { it.copy(groupsSupported = ok) }
            rooms.value = if (ok) groups.rooms() else emptyList()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Group listing is optional; the bots still show.
        }
    }

    fun toggleSearch() {
        searchJob?.cancel()
        _ui.update { it.copy(searching = !it.searching, query = "", searchResults = null) }
    }

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
            _ui.update { it.copy(searchResults = results.sortedByDescending { s -> s.lastActive }) }
        }
    }

    fun pin(row: InboxRow, pinned: Boolean) {
        val id = row.sessionId ?: return
        viewModelScope.launch {
            runCatching { api.patchSession(row.profile.takeIf { it != "default" }, id, pinned = pinned) }
                .onFailure { e -> _ui.update { it.copy(error = e.message) } }
            load(quiet = true)
        }
    }

    /** Opening a row: groups are tracked locally; bots are marked read by the chat screen itself. */
    fun markRead(row: InboxRow) {
        if (row.kind == InboxKind.GROUP) viewModelScope.launch { prefs.markSeen(row.key) }
    }
}
