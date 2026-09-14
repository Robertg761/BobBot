package com.bobbot.ui.team

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobbot.core.net.*
import com.bobbot.data.repo.BotsRepository
import com.bobbot.data.repo.BotNames
import com.bobbot.data.repo.botName
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.theme.BobColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import javax.inject.Inject

data class GroupsUi(
    val rooms: List<JsonElement> = emptyList(), val profiles: List<String> = emptyList(),
    val selected: String? = null, val events: List<JsonElement> = emptyList(),
    val actions: List<JsonElement> = emptyList(), val working: Boolean = false,
    val busy: Boolean = false, val error: String? = null, val supported: Boolean = true,
)

@HiltViewModel
class GroupsViewModel @Inject constructor(private val socket: GatewaySocket, private val bots: BotsRepository) : ViewModel() {
    val ui = MutableStateFlow(GroupsUi())
    private val lock = Mutex()
    private var cursor = 0L
    private var sendAttempt: Triple<String, String, String>? = null
    private var createAttempt: Pair<List<String>, String>? = null
    private suspend fun rpc(method: String, params: kotlinx.serialization.json.JsonObject = jsonOf()): JsonElement {
        socket.ensureConnected()
        return socket.call(method, params)
    }
    suspend fun refresh() = lock.withLock {
        try {
            val capabilities = rpc("groups.capabilities")
            check(capabilities.bool("driver") == true) { "Hermes' group worker is unavailable. Restart the dashboard and retry." }
            if (ui.value.profiles.isEmpty()) ui.update { it.copy(profiles = bots.refresh().map { b -> b.name }) }
            val rooms = rpc("groups.list").list("rooms")
            ui.update { it.copy(rooms = rooms, error = null, supported = true) }
            val room = ui.value.selected ?: return@withLock
            do {
                val page = rpc("groups.log", jsonOf("room_id" to room, "since_seq" to cursor, "limit" to 100))
                ui.update { it.copy(events = (it.events + page.list("events")).distinctBy { e -> e.long("seq") }) }
                val next = page.long("cursor") ?: cursor
                val more = page.bool("has_more") == true && next > cursor
                cursor = next
            } while (more)
            val status = rpc("groups.state", jsonOf("room_id" to room)).child("driver_status")
            ui.update { it.copy(actions = status.list("pending_actions"), working = status.bool("working") == true) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ui.update { it.copy(error = e.message, supported = !(e is RpcException && e.code == -32601)) }
        }
    }
    fun select(id: String?) { viewModelScope.launch { lock.withLock { cursor = 0; ui.update { it.copy(selected = id, events = emptyList(), actions = emptyList()) } }; refresh() } }
    private fun act(work: suspend () -> Unit) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { lock.withLock { work() }; refresh() }
            catch (e: Exception) { ui.update { it.copy(error = e.message) } }
            finally { ui.update { it.copy(busy = false) } }
        }
    }
    fun create(profiles: List<String>) = act {
        require(profiles.size in 2..6) { "Choose between two and six bots." }
        val id = createAttempt?.takeIf { it.first == profiles }?.second ?: UUID.randomUUID().toString().also { createAttempt = profiles to it }
        rpc("groups.create", jsonOf("room_id" to id, "name" to profiles.joinToString(" + ") { BotNames.display(it) },
            "members" to profiles.map { jsonOf("member_id" to it, "profile" to it, "handle" to it, "display_name" to BotNames.display(it)) }))
        cursor = 0
        ui.update { it.copy(selected = id, events = emptyList(), actions = emptyList()) }
        createAttempt = null
    }
    fun send(text: String, onSent: () -> Unit) = act {
        val room = ui.value.selected ?: return@act
        if (text.isBlank()) return@act
        val id = sendAttempt?.takeIf { it.first == room && it.second == text }?.third
            ?: UUID.randomUUID().toString().also { sendAttempt = Triple(room, text, it) }
        rpc("groups.send", jsonOf("room_id" to room, "event_id" to id, "payload" to jsonOf("text" to text, "thread_id" to "main")))
        sendAttempt = null
        onSent()
    }
    fun stop() = act { rpc("groups.stop", jsonOf("room_id" to ui.value.selected, "cancel_id" to UUID.randomUUID().toString())) }
    fun respond(action: JsonElement, choice: String) = act {
        rpc(if (action.str("kind") == "retry") "groups.retry" else "groups.approve",
            jsonOf("room_id" to ui.value.selected, "task_id" to action.str("task_id"), "member_id" to action.str("member_id"),
                "execution_generation" to action.int("execution_generation"), "request_id" to action.str("request_id"), "choice" to choice))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(onBack: () -> Unit) {
    val vm: GroupsViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var chosen by rememberSaveable { mutableStateOf(listOf<String>()) }
    var input by rememberSaveable(ui.selected) { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { vm.refresh(); delay(3000) } }
    Scaffold(containerColor = BobColors.Bg, topBar = {
        TopAppBar(title = { Text(if (ui.selected == null) "Group conversations" else "Team conversation") }, navigationIcon = {
            IconButton(onClick = { if (ui.selected != null) vm.select(null) else onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        }, actions = { if (ui.selected != null) TextButton(onClick = vm::stop, enabled = !ui.busy) { Text("Stop") } })
    }, bottomBar = {
        if (ui.selected != null) Row(Modifier.navigationBarsPadding().imePadding().padding(12.dp)) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message the group, or @mention a profile") }, enabled = !ui.busy)
            TextButton(onClick = { val sent = input; vm.send(sent) { if (input == sent) input = "" } }, enabled = !ui.busy && input.isNotBlank()) { Text("Send") }
        }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ui.error?.let { item { Text(it, color = BobColors.Rose) } }
            if (ui.selected == null) {
                item { Text("Conversations run on Hermes and remain available when your phone disconnects.") }
                items(ui.rooms, key = { it.str("room_id")!! }) { room ->
                    BobCard(onClick = { vm.select(room.str("room_id")) }) { Text(room.str("name") ?: "Group") }
                }
                item { Text("Start a group", style = MaterialTheme.typography.titleLarge) }
                items(ui.profiles) { profile ->
                    Row { Checkbox(profile in chosen, { checked -> chosen = if (checked) (chosen + profile).distinct() else chosen - profile }, enabled = !ui.busy)
                        Text(botName(profile), Modifier.padding(top = 12.dp)) }
                }
                item { Button(onClick = { vm.create(chosen) }, enabled = !ui.busy && ui.supported && chosen.size in 2..6) { Text("Create conversation") } }
                if (ui.profiles.size < 2) item { Text("Create another bot from the Bots tab to start a group.") }
            } else {
                if (ui.working) item { Text("The team is working…", color = BobColors.Accent) }
                items(ui.events, key = { it.long("seq")!! }) { event ->
                    val payload = event.child("payload")
                    val text = payload.str("text") ?: payload.str("message")
                    if (!text.isNullOrBlank()) BobCard {
                        val actor = event.child("actor")
                        Text(actor.str("display_name") ?: actor.str("profile")?.let { BotNames.display(it) } ?: actor.str("kind") ?: "Team", color = BobColors.Accent)
                        Text(text)
                    }
                }
                items(ui.actions) { action ->
                    BobCard {
                        Text("${action.str("member_id") ?: "Bot"} needs attention")
                        if (action.str("kind") == "retry") TextButton(onClick = { vm.respond(action, "") }, enabled = !ui.busy) { Text("Retry task") }
                        else {
                            Text(action.child("approval").str("description") ?: "Permission requested")
                            Text(action.child("approval").str("command") ?: "", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { vm.respond(action, "once") }, enabled = !ui.busy) { Text("Allow once") }
                                TextButton(onClick = { vm.respond(action, "deny") }, enabled = !ui.busy) { Text("Deny") }
                            }
                        }
                    }
                }
            }
        }
    }
}
