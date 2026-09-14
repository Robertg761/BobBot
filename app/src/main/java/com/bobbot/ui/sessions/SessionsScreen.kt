package com.bobbot.ui.sessions

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobbot.data.model.SessionSummary
import com.bobbot.ui.board.relativeTime
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.components.EmptyState
import com.bobbot.ui.components.LoadingRow
import com.bobbot.ui.components.Pill
import com.bobbot.ui.components.StatusDot
import com.bobbot.ui.setup.fieldColors
import com.bobbot.data.repo.botName
import com.bobbot.ui.theme.BobColors
import com.bobbot.ui.theme.botColor

@Composable
fun SessionsScreen(
    onOpenChat: (sessionId: String, profile: String) -> Unit,
    onNewChat: (profile: String) -> Unit,
    onOpenBots: () -> Unit,
    vm: SessionsViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    var pickBot by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BobColors.Bg,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (ui.bots.size <= 1) onNewChat(ui.bots.firstOrNull()?.name ?: "default") else pickBot = true },
                containerColor = BobColors.Accent, contentColor = BobColors.Bg,
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New chat", fontWeight = FontWeight.SemiBold) },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Chats", style = MaterialTheme.typography.headlineMedium, color = BobColors.Text)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(ui.connected)
                        Spacer(Modifier.width(6.dp))
                        Text(if (ui.connected == true) "Connected to Hermes" else if (ui.connected == false) "Offline" else "Connecting…", style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted)
                    }
                }
                IconButton(onClick = { vm.toggleSearch() }) { Icon(Icons.Outlined.Search, "Search", tint = BobColors.TextMuted) }
                IconButton(onClick = { vm.load() }) { Icon(Icons.Outlined.Refresh, "Refresh", tint = BobColors.TextMuted) }
            }
            if (ui.searching) {
                OutlinedTextField(
                    value = ui.query, onValueChange = vm::setQuery, singleLine = true,
                    placeholder = { Text("Search all chats") }, colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Pill("All bots", color = if (ui.filter == null) BobColors.Accent else BobColors.TextMuted, onClick = { vm.setFilter(null) }) }
                items(ui.bots) { b ->
                    Pill(botName(b.name), color = if (ui.filter == b.name) botColor(b.name) else BobColors.TextMuted, onClick = { vm.setFilter(b.name) })
                }
                item { Pill("+ Bots", color = BobColors.Mint, onClick = onOpenBots) }
            }
            when {
                ui.loading && ui.sessions.isEmpty() -> LoadingRow("Loading chats…")
                ui.error != null && ui.sessions.isEmpty() -> EmptyState("Couldn't load chats", ui.error)
                ui.filtered.isEmpty() -> EmptyState("No chats yet", "Start one with the button below", Icons.Outlined.ChatBubbleOutline)
                else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ui.filtered, key = { it.profile + ":" + it.id }) { s ->
                        SessionRow(s, onClick = { onOpenChat(s.id, s.profile) }, vm = vm)
                    }
                }
            }
        }
    }

    if (pickBot) {
        AlertDialog(
            onDismissRequest = { pickBot = false },
            confirmButton = { TextButton(onClick = { pickBot = false }) { Text("Cancel") } },
            title = { Text("Chat with which bot?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ui.bots.forEach { b ->
                        BobCard(onClick = { pickBot = false; onNewChat(b.name) }, padding = PaddingValues(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BotAvatar(b.name, 32.dp); Spacer(Modifier.width(12.dp))
                                Column { Text(botName(b.name), color = BobColors.Text, fontWeight = FontWeight.SemiBold); Text(b.model, style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted) }
                            }
                        }
                    }
                }
            },
            containerColor = BobColors.SurfaceRaised,
        )
    }
}

@Composable
private fun SessionRow(s: SessionSummary, onClick: () -> Unit, vm: SessionsViewModel) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    BobCard(onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(s.profile, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title.ifBlank { s.preview.ifBlank { "New chat" } }, color = BobColors.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (s.pinned) { Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.PushPin, null, tint = BobColors.Amber, modifier = Modifier.height(14.dp)) }
                }
                Spacer(Modifier.height(2.dp))
                Text(s.preview.ifBlank { s.model }, color = BobColors.TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(botName(s.profile), color = botColor(s.profile), style = MaterialTheme.typography.labelSmall)
                    Text("·", color = BobColors.TextFaint)
                    Text(relativeTime(s.lastActive), color = BobColors.TextFaint, style = MaterialTheme.typography.labelSmall)
                    if (s.messageCount > 0) { Text("·", color = BobColors.TextFaint); Text("${s.messageCount} msgs", color = BobColors.TextFaint, style = MaterialTheme.typography.labelSmall) }
                    if (s.source.isNotBlank() && s.source != "cli" && s.source != "desktop") Pill(s.source, color = BobColors.TextMuted)
                }
            }
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.Edit, "More", tint = BobColors.TextFaint, modifier = Modifier.height(18.dp)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                DropdownMenuItem(text = { Text(if (s.pinned) "Unpin" else "Pin") }, onClick = { menu = false; vm.pin(s, !s.pinned) }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) })
                DropdownMenuItem(text = { Text("Archive") }, onClick = { menu = false; vm.archive(s) }, leadingIcon = { Icon(Icons.Outlined.Archive, null) })
                DropdownMenuItem(text = { Text("Delete", color = BobColors.Rose) }, onClick = { menu = false; vm.delete(s) }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = BobColors.Rose) })
            }
        }
    }
    if (renaming) {
        var t by remember { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            confirmButton = { TextButton(onClick = { renaming = false; vm.rename(s, t) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true, colors = fieldColors()) },
            containerColor = BobColors.SurfaceRaised,
        )
    }
}
