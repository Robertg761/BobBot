package com.bobbot.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bobbot.core.net.child
import com.bobbot.core.net.str
import com.bobbot.data.prefs.AppPrefs
import com.bobbot.data.repo.BotNames
import com.bobbot.data.repo.GroupEvent
import com.bobbot.data.repo.GroupRoom
import com.bobbot.data.repo.GroupsRepository
import com.bobbot.data.repo.botName
import com.bobbot.ui.chat.Composer
import com.bobbot.ui.chat.MarkdownBody
import com.bobbot.ui.chat.TypingBubble
import com.bobbot.ui.chat.bubbleShape
import com.bobbot.ui.chat.gapBetween
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.components.GroupAvatar
import com.bobbot.data.repo.InboxKeys
import com.bobbot.ui.theme.BobColors
import com.bobbot.ui.theme.botColor
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

data class GroupChatUi(
    val room: GroupRoom? = null,
    val events: List<GroupEvent> = emptyList(),
    val actions: List<JsonElement> = emptyList(),
    val working: Boolean = false,
    val busy: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val input: String = "",
)

@HiltViewModel
class GroupChatViewModel @Inject constructor(private val groups: GroupsRepository, private val prefs: AppPrefs) : ViewModel() {
    val ui = MutableStateFlow(GroupChatUi())
    private val lock = Mutex()
    private var roomId: String = ""
    private var cursor = 0L
    private var sendAttempt: Pair<String, String>? = null

    fun bind(id: String) {
        if (roomId == id) return
        roomId = id
        cursor = 0
        ui.value = GroupChatUi()
        viewModelScope.launch { runCatching { prefs.markSeen(InboxKeys.room(id)) } }
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(if (ui.value.working) 2000 else 4000)
            }
        }
    }

    fun setInput(v: String) = ui.update { it.copy(input = v) }

    suspend fun refresh() = lock.withLock {
        val id = roomId.ifBlank { return@withLock }
        try {
            if (ui.value.room == null) ui.update { s -> s.copy(room = groups.rooms().firstOrNull { it.id == id }) }
            do {
                val (events, next, more) = groups.log(id, cursor)
                ui.update { s -> s.copy(events = (s.events + events).distinctBy { e -> e.seq }.sortedBy { e -> e.seq }) }
                cursor = next
            } while (more)
            val status = groups.status(id)
            ui.update { it.copy(actions = status.pendingActions, working = status.working, loading = false, error = null) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ui.update { it.copy(error = e.message, loading = false) }
        }
    }

    private fun act(work: suspend () -> Unit) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { lock.withLock { work() }; refresh() }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; ui.update { it.copy(error = e.message) } }
            finally { ui.update { it.copy(busy = false) } }
        }
    }

    fun send() {
        val text = ui.value.input.trim()
        if (text.isBlank()) return
        act {
            val id = sendAttempt?.takeIf { it.first == text }?.second ?: UUID.randomUUID().toString().also { sendAttempt = text to it }
            groups.send(roomId, id, text)
            sendAttempt = null
            ui.update { it.copy(input = "") }
        }
    }

    fun stop() = act { groups.stop(roomId, UUID.randomUUID().toString()) }
    fun respond(action: JsonElement, choice: String) = act { groups.respond(roomId, action, choice) }

    override fun onCleared() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { prefs.markSeen(InboxKeys.room(roomId)) } }
        super.onCleared()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(roomId: String, onBack: () -> Unit, onOpenBot: (String) -> Unit) {
    val vm: GroupChatViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(roomId) { vm.bind(roomId) }
    val listState = rememberLazyListState()
    val messages = ui.events.filter { it.isMessage && it.text.isNotBlank() }
    val extra = (if (ui.working) 1 else 0) + ui.actions.size + 1
    LaunchedEffect(messages.size, ui.working, ui.actions.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size + extra)
    }
    val members = ui.room?.members ?: emptyList()
    val title = ui.room?.name?.ifBlank { null } ?: members.joinToString(", ") { BotNames.display(it) }.ifBlank { "Group" }

    Scaffold(
        containerColor = BobColors.Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BobColors.Bg),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = BobColors.Text) } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GroupAvatar(members, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(title, style = MaterialTheme.typography.titleMedium, color = BobColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (ui.working) "Working…" else "${members.size} bots",
                                style = MaterialTheme.typography.labelSmall, color = if (ui.working) BobColors.Amber else BobColors.TextMuted,
                            )
                        }
                    }
                },
                actions = {
                    if (ui.working) IconButton(onClick = vm::stop, enabled = !ui.busy) { Icon(Icons.Outlined.Stop, "Stop", tint = BobColors.Rose) }
                },
            )
        },
        bottomBar = {
            Column {
                MentionChips(input = ui.input, members = members, onPick = vm::setInput)
                Composer(
                    input = ui.input, onInput = vm::setInput, onSend = vm::send, onStop = vm::stop,
                    busy = false, attachments = emptyList(), attaching = false, onAttach = null,
                    enabled = !ui.busy && ui.room != null, notice = null, placeholder = "Message the group, or @mention a bot",
                )
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                ui.loading && messages.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = BobColors.Accent)
                    Spacer(Modifier.height(12.dp))
                    Text("Opening group…", color = BobColors.TextMuted)
                }
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
                    ui.error?.let { err -> item(key = "error") { Text(err, color = BobColors.Rose, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) } }
                    if (messages.isEmpty()) item(key = "intro") {
                        Column(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            GroupAvatar(members, 84.dp)
                            Spacer(Modifier.height(14.dp))
                            Text(title, style = MaterialTheme.typography.headlineSmall, color = BobColors.Text)
                            Spacer(Modifier.height(6.dp))
                            Text("Hermes hosts this conversation and keeps it going when your phone is away.", color = BobColors.TextFaint, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    messages.forEachIndexed { i, m ->
                        val above = messages.getOrNull(i - 1)
                        val below = messages.getOrNull(i + 1)
                        val groupedAbove = above != null && above.fromUser == m.fromUser && above.profile == m.profile
                        val groupedBelow = below != null && below.fromUser == m.fromUser && below.profile == m.profile
                        item(key = m.seq) {
                            Spacer(Modifier.height(if (i == 0) 0.dp else gapBetween(groupedAbove)))
                            GroupMessage(m, groupedAbove, groupedBelow, onOpenBot)
                        }
                    }
                    if (ui.working) item(key = "typing") {
                        Spacer(Modifier.height(12.dp))
                        Row { Spacer(Modifier.width(36.dp)); TypingBubble(color = BobColors.TextMuted) }
                    }
                    ui.actions.forEach { action ->
                        item(key = "action:" + (action.str("request_id") ?: action.str("task_id") ?: action.hashCode())) {
                            Spacer(Modifier.height(12.dp))
                            ActionCard(action, busy = ui.busy, onRespond = { choice -> vm.respond(action, choice) })
                        }
                    }
                    item(key = "tail") { Spacer(Modifier.height(4.dp)) }
                }
            }
        }
    }
}

/** While the last word starts with "@", offer the members whose handle matches; tapping completes it. */
@Composable
private fun MentionChips(input: String, members: List<String>, onPick: (String) -> Unit) {
    val token = input.substringAfterLast(' ').substringAfterLast('\n')
    if (!token.startsWith("@") || members.isEmpty()) return
    val typed = token.drop(1).lowercase()
    val handles = members.map { p -> (if (p == "default") "hermes" else p) to p }.filter { (h, p) -> h.startsWith(typed) || botName(p).lowercase().startsWith(typed) }
    if (handles.isEmpty()) return
    androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        items(handles.size, key = { handles[it].first }) { i ->
            val (handle, profile) = handles[i]
            com.bobbot.ui.components.Pill("@$handle · ${botName(profile)}", color = botColor(profile), onClick = {
                onPick(input.dropLast(token.length) + "@" + handle + " ")
            })
        }
    }
}

@Composable
private fun GroupMessage(m: GroupEvent, groupedAbove: Boolean, groupedBelow: Boolean, onOpenBot: (String) -> Unit) {
    if (m.fromUser) {
        Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End) {
            Box(
                Modifier.widthIn(max = 300.dp).clip(bubbleShape(mine = true, groupedAbove = groupedAbove, groupedBelow = groupedBelow))
                    .background(BobColors.UserBubble).padding(horizontal = 14.dp, vertical = 9.dp),
            ) { Text(m.text, color = BobColors.UserBubbleText, style = MaterialTheme.typography.bodyLarge) }
        }
        return
    }
    val profile = m.profile ?: ""
    val name = m.displayName?.takeIf { it.isNotBlank() } ?: profile.takeIf { it.isNotBlank() }?.let { botName(it) } ?: "Bot"
    Row(Modifier.fillMaxWidth().padding(end = 32.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.width(36.dp)) {
            if (!groupedBelow && profile.isNotBlank()) BotAvatar(profile, 28.dp, modifier = Modifier.clickable { onOpenBot(profile) })
        }
        Column {
            if (!groupedAbove) Text(name, style = MaterialTheme.typography.labelSmall, color = botColor(profile), modifier = Modifier.padding(start = 6.dp, bottom = 3.dp))
            Box(
                Modifier.widthIn(max = 300.dp).clip(bubbleShape(mine = false, groupedAbove = groupedAbove, groupedBelow = groupedBelow))
                    .background(BobColors.BotBubble).padding(horizontal = 14.dp, vertical = 9.dp),
            ) { MarkdownBody(m.text) }
        }
    }
}

@Composable
private fun ActionCard(action: JsonElement, busy: Boolean, onRespond: (String) -> Unit) {
    val member = action.str("member_id")?.let { BotNames.display(it) } ?: "A bot"
    Row(Modifier.fillMaxWidth().padding(end = 24.dp)) {
        BobCard(container = BobColors.SurfaceRaised, border = BobColors.Amber.copy(alpha = 0.35f), padding = PaddingValues(14.dp)) {
            if (action.str("kind") == "retry") {
                Text("$member hit a problem", style = MaterialTheme.typography.titleSmall, color = BobColors.Text)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onRespond("") }, enabled = !busy) { Text("Retry task") }
            } else {
                Text("$member wants to run a command", style = MaterialTheme.typography.titleSmall, color = BobColors.Text)
                action.child("approval").str("description")?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp)); Text(it, color = BobColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                action.child("approval").str("command")?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(BobColors.Bg).padding(10.dp)) {
                        Text(it, color = BobColors.Text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onRespond("once") }, enabled = !busy) { Text("Allow") }
                    TextButton(onClick = { onRespond("deny") }, enabled = !busy) { Text("Deny", color = BobColors.Rose) }
                }
            }
        }
    }
}
