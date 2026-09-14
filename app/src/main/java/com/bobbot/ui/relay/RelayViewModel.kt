package com.bobbot.ui.relay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobbot.data.model.Bot
import com.bobbot.data.repo.BotsRepository
import com.bobbot.data.repo.RelayRepository
import com.bobbot.data.repo.RelayState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RelayUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val bots: List<Bot> = emptyList(),
    val relay: RelayState = RelayState(),
)

@HiltViewModel
class RelayViewModel @Inject constructor(
    private val relay: RelayRepository,
    private val bots: BotsRepository,
) : ViewModel() {

    private val local = MutableStateFlow(RelayUiState())

    val ui: StateFlow<RelayUiState> = combine(local, bots.bots, relay.state) { own, botList, relayState ->
        own.copy(bots = botList, relay = relayState)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RelayUiState())

    init { loadBots() }

    fun loadBots() {
        viewModelScope.launch {
            local.update { it.copy(loading = true, error = null) }
            try {
                bots.refresh()
                local.update { it.copy(loading = false) }
            } catch (e: Exception) {
                local.update { it.copy(loading = false, error = e.message ?: "Could not load bots") }
            }
        }
    }

    fun start(botA: String, botB: String, topic: String, rounds: Int) {
        if (botA.isBlank() || botB.isBlank() || botA == botB) return
        relay.start(botA, botB, topic.trim(), rounds.coerceIn(1, 12))
    }

    /** Run the same pairing and topic again from scratch. */
    fun restart() {
        val s = relay.state.value
        if (s.botA.isBlank() || s.botB.isBlank()) return
        relay.start(s.botA, s.botB, s.topic, s.maxRounds)
    }

    fun stop() = relay.stop()

    fun interject(text: String) {
        if (text.isBlank()) return
        relay.interject(text.trim())
    }
}
