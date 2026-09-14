package com.bobbot.ui.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bobbot.data.repo.TeamRepository
import com.bobbot.data.repo.TeamState
import com.bobbot.data.repo.botName
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.components.EmptyState
import com.bobbot.ui.components.LoadingRow
import com.bobbot.ui.components.Pill
import com.bobbot.ui.components.SectionHeader
import com.bobbot.ui.theme.BobColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TeamViewModel @Inject constructor(private val team: TeamRepository) : ViewModel() {
    val state: StateFlow<TeamState?> = team.state
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    suspend fun refresh() {
        try { team.refresh(); error.value = null } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error.value = e.message
        }
    }
    fun decide(id: String, choice: String, scope: String) = act { team.decide(id, choice, scope) }
    fun configure(authority: String, enabled: Boolean) = act { team.configure(authority, enabled) }
    fun bootstrap(profile: String) = act { team.bootstrap(profile) }

    private fun act(work: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { work(); error.value = null } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error.value = e.message }
            finally { busy.value = false }
        }
    }
}

/**
 * Who reviews what, and every decision that is waiting. Requests for you come first, then the
 * ones the authority is still looking at, then the team, then history folded away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(onBack: () -> Unit, onChat: (String) -> Unit) {
    val vm: TeamViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var showHistory by rememberSaveable { mutableStateOf(false) }
    com.bobbot.ui.components.PollWhileStarted(Unit, 5_000) { vm.refresh() }

    Scaffold(
        containerColor = BobColors.Bg,
        topBar = {
            TopAppBar(
                title = { Text("Team & permissions") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BobColors.Bg),
            )
        },
    ) { pad ->
        val s = state
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { item(key = "error") { Text(it, color = BobColors.Rose, style = MaterialTheme.typography.bodySmall) } }
            when {
                s == null -> item(key = "loading") { LoadingRow("Loading the team…") }
                !s.installed -> item(key = "missing") {
                    EmptyState("Team extension not installed", "Install server/bobbot-team on your Hermes to let one bot review what the others do.")
                }
                else -> {
                    item(key = "authority") {
                        BobCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BotAvatar(s.authority, 44.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(botName(s.authority), style = MaterialTheme.typography.titleMedium, color = BobColors.Text)
                                    Text("Reviews what the other bots do and decides their permissions. Anything unclear comes to you.", style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Review specialists' actions", color = BobColors.Text, style = MaterialTheme.typography.bodyLarge)
                                    Text(if (s.enabled) "On. Read-only lookups and messages between bots pass without review." else "Off. Bots only follow Hermes' own approval rules.", color = BobColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = s.enabled, onCheckedChange = { vm.configure(s.authority, it) }, enabled = !busy)
                            }
                            TextButton(onClick = { onChat(s.authority) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Icon(Icons.Outlined.Forum, null, tint = BobColors.Accent, modifier = Modifier.width(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Message ${botName(s.authority)}", color = BobColors.Accent)
                            }
                        }
                    }

                    val forYou = s.needingYou
                    if (forYou.isNotEmpty()) {
                        item(key = "you-header") { SectionHeader("Needs you") }
                        items(forYou, key = { "y:" + it.id }) { r ->
                            Column {
                                PermissionCard(r, s.authority, busy, onDecide = { c, sc -> vm.decide(r.id, c, sc) })
                                // Deciding inside the bot's chat keeps that chat live, so any follow-up prompt from Hermes itself lands on the phone.
                                TextButton(onClick = { onChat(r.profile) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                    Text("Open ${botName(r.profile)}'s chat to decide there", color = BobColors.Accent, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    val waiting = s.waiting
                    if (waiting.isNotEmpty()) {
                        item(key = "wait-header") { SectionHeader("Waiting for ${botName(s.authority)}") }
                        items(waiting, key = { "w:" + it.id }) { r -> PermissionCard(r, s.authority, busy, onDecide = { c, sc -> vm.decide(r.id, c, sc) }) }
                    }
                    if (forYou.isEmpty() && waiting.isEmpty()) {
                        item(key = "clear") {
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.CheckCircle, null, tint = BobColors.Mint, modifier = Modifier.width(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Nothing is waiting on anyone.", color = BobColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    item(key = "team-header") { SectionHeader("Team") }
                    item(key = "team") {
                        BobCard(padding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)) {
                            s.profiles.forEach { name ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    BotAvatar(name, 36.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text(botName(name), style = MaterialTheme.typography.bodyLarge, color = BobColors.Text, modifier = Modifier.weight(1f))
                                    if (name == s.authority) Pill("authority", color = BobColors.Accent)
                                    else TextButton(onClick = { vm.configure(name, s.enabled) }, enabled = !busy) { Text("Make authority", color = BobColors.TextMuted) }
                                    TextButton(onClick = { vm.bootstrap(name) }, enabled = !busy) { Text("Set up", color = BobColors.Accent) }
                                }
                            }
                            Text(
                                "Set up links the team extension into a bot and enables its tools. Bots created in BobBot get this automatically.",
                                style = MaterialTheme.typography.bodySmall, color = BobColors.TextFaint, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }

                    val history = s.requests.filterNot { it.open }
                    if (history.isNotEmpty()) {
                        item(key = "history-header") {
                            SectionHeader("History", trailing = { TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "Hide" else "Show ${history.size}", color = BobColors.Accent) } })
                        }
                        if (showHistory) items(history.take(40), key = { "h:" + it.id }) { r -> PermissionCard(r, s.authority, busy, onDecide = { _, _ -> }, showHistory = true) }
                    }
                }
            }
        }
    }
}
