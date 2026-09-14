package com.bobbot.ui.inbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobbot.data.model.SessionSummary
import com.bobbot.data.repo.botName
import com.bobbot.ui.board.relativeTime
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.components.EmptyState
import com.bobbot.ui.components.GroupAvatar
import com.bobbot.ui.components.LoadingRow
import com.bobbot.ui.components.PresenceAvatar
import com.bobbot.ui.setup.fieldColors
import com.bobbot.ui.theme.BobColors

/** Callbacks out of the inbox. Everything else in the app hangs off this screen. */
data class InboxActions(
    val openBot: (profile: String) -> Unit,
    val openTaskChat: (sessionId: String?, profile: String) -> Unit,
    val openGroup: (roomId: String) -> Unit,
    val openBotProfile: (profile: String) -> Unit,
    val newBot: () -> Unit,
    val newGroup: () -> Unit,
    val openNetwork: () -> Unit,
    val openAutomations: () -> Unit,
    val openSettings: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(actions: InboxActions, vm: InboxViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    var showNew by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(containerColor = BobColors.Bg) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Messages", style = MaterialTheme.typography.headlineMedium, color = BobColors.Text)
                    val sub = when (ui.connected) {
                        true -> null
                        false -> "Offline · trying to reconnect"
                        null -> "Connecting…"
                    }
                    if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted)
                }
                IconButton(onClick = vm::toggleSearch) {
                    Icon(if (ui.searching) Icons.Outlined.Close else Icons.Outlined.Search, "Search", tint = BobColors.TextMuted)
                }
                IconButton(onClick = { showNew = true }) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(BobColors.Accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, "New", tint = BobColors.Bg)
                    }
                }
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = BobColors.TextMuted) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = BobColors.SurfaceRaised) {
                        DropdownMenuItem(text = { Text("Bot network") }, leadingIcon = { Icon(Icons.Outlined.Hub, null) }, onClick = { showMenu = false; actions.openNetwork() })
                        DropdownMenuItem(text = { Text("Automations") }, leadingIcon = { Icon(Icons.Outlined.Schedule, null) }, onClick = { showMenu = false; actions.openAutomations() })
                        DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, onClick = { showMenu = false; actions.openSettings() })
                    }
                }
            }

            if (ui.searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = ui.query, onValueChange = vm::setQuery, singleLine = true,
                    placeholder = { Text("Search bots and chats") }, colors = fieldColors(), shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).focusRequester(focus),
                )
            }

            val rows = ui.visible
            when {
                ui.loading && rows.isEmpty() -> LoadingRow("Loading your bots…")
                ui.error != null && rows.isEmpty() -> EmptyState("Couldn't reach Hermes", ui.error)
                rows.isEmpty() && ui.query.isBlank() -> EmptyState("No bots yet", "Tap + to create your first bot.", Icons.Outlined.SmartToy)
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { it.key }) { row ->
                        InboxRowItem(
                            row = row,
                            onOpen = {
                                vm.markRead(row)
                                if (row.kind == InboxKind.GROUP) actions.openGroup(row.roomId) else actions.openBot(row.profile)
                            },
                            onPin = { vm.pin(row, !row.pinned) },
                            onProfile = { actions.openBotProfile(row.profile) },
                            onTaskChat = { actions.openTaskChat(null, row.profile) },
                        )
                        HorizontalDivider(color = BobColors.OutlineSoft, modifier = Modifier.padding(start = 84.dp))
                    }
                    val results = ui.searchResults
                    if (ui.query.isNotBlank() && !results.isNullOrEmpty()) {
                        item(key = "search-header") {
                            Text(
                                "TASK CHATS", style = MaterialTheme.typography.labelMedium, color = BobColors.TextFaint,
                                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
                            )
                        }
                        items(results, key = { "s:" + it.profile + ":" + it.id }) { s ->
                            SearchResultRow(s) { actions.openTaskChat(s.id, s.profile) }
                        }
                    } else if (ui.query.isNotBlank() && rows.isEmpty()) {
                        item(key = "no-results") { EmptyState("Nothing matches", "Try another name or phrase.", Icons.Outlined.Search) }
                    }
                }
            }
        }
    }

    if (showNew) {
        ModalBottomSheet(onDismissRequest = { showNew = false }, containerColor = BobColors.SurfaceRaised) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).navigationBarsPadding()) {
                NewOption(Icons.Outlined.AutoAwesome, "New bot", "Give it a name, a persona and a model.") { showNew = false; actions.newBot() }
                NewOption(Icons.Outlined.Group, "New group", "Put two to six bots in one conversation.", enabled = ui.groupsSupported && ui.bots.size >= 2) { showNew = false; actions.newGroup() }
                if (!ui.groupsSupported) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, tint = BobColors.TextFaint, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Groups need Hermes 0.21.2 with the group worker running.", style = MaterialTheme.typography.bodySmall, color = BobColors.TextFaint)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun NewOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .then(if (enabled) Modifier.combinedClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(BobColors.AccentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (enabled) BobColors.Accent else BobColors.TextFaint)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) BobColors.Text else BobColors.TextFaint)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxRowItem(row: InboxRow, onOpen: () -> Unit, onPin: () -> Unit, onProfile: () -> Unit, onTaskChat: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.kind == InboxKind.GROUP) GroupAvatar(row.members, 52.dp) else PresenceAvatar(row.profile, row.presence, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.title, style = MaterialTheme.typography.titleMedium, color = BobColors.Text,
                        fontWeight = if (row.unread) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.pinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.PushPin, null, tint = BobColors.TextFaint, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (row.lastActive > 0) {
                        Text(
                            relativeTime(row.lastActive / 1000.0), style = MaterialTheme.typography.labelSmall,
                            color = if (row.unread) BobColors.Accent else BobColors.TextFaint,
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (preview, color) = when (row.presence) {
                        Presence.WAITING -> "Needs your input" to BobColors.Rose
                        Presence.WORKING -> (if (row.preview.startsWith("You: ")) "Working…" else row.preview) to BobColors.Amber
                        Presence.IDLE -> row.preview to if (row.unread) BobColors.Text else BobColors.TextMuted
                    }
                    Text(
                        preview, style = MaterialTheme.typography.bodyMedium, color = color,
                        fontWeight = if (row.unread) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (row.unread) {
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.size(10.dp).clip(CircleShape).background(BobColors.Accent))
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = BobColors.SurfaceRaised) {
            if (row.kind == InboxKind.BOT) {
                if (row.sessionId != null) {
                    DropdownMenuItem(text = { Text(if (row.pinned) "Unpin" else "Pin") }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) }, onClick = { menu = false; onPin() })
                }
                DropdownMenuItem(text = { Text("New task chat") }, leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) }, onClick = { menu = false; onTaskChat() })
                DropdownMenuItem(text = { Text("Bot profile") }, leadingIcon = { Icon(Icons.Outlined.Info, null) }, onClick = { menu = false; onProfile() })
            } else {
                DropdownMenuItem(text = { Text("Open group") }, leadingIcon = { Icon(Icons.Outlined.Group, null) }, onClick = { menu = false; onOpen() })
            }
        }
    }
}

@Composable
private fun SearchResultRow(s: SessionSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotAvatar(s.profile, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title.ifBlank { s.preview.ifBlank { "Untitled chat" } }, style = MaterialTheme.typography.titleSmall, color = BobColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${botName(s.profile)} · ${relativeTime(s.lastActive)}", style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
