package com.bobbot.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobbot.core.net.SocketState
import com.bobbot.data.repo.ChatItem
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.components.Pill
import com.bobbot.ui.models.ModelPickerSheet
import com.bobbot.ui.setup.fieldColors
import com.bobbot.data.repo.botName
import com.bobbot.ui.theme.BobColors
import com.bobbot.ui.theme.botColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    profile: String,
    mainConversation: Boolean = false,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    vm: ChatViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(sessionId, profile) { vm.open(sessionId, profile, mainConversation) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(ui.toast) { ui.toast?.let { snack.showSnackbar(it); vm.clearToast() } }
    val listState = rememberLazyListState()
    val session = ui.session
    val items = session?.items ?: emptyList()
    var renaming by remember { mutableStateOf(false) }

    // Follow the stream.
    LaunchedEffect(items.size, (items.lastOrNull() as? ChatItem.Assistant)?.text?.length) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1 + 1)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::attach) }
    val color = botColor(profile)
    val botLabel = botName(profile)

    Scaffold(
        containerColor = BobColors.Bg,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BobColors.Bg),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = BobColors.Text) } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { renaming = true }) {
                        BotAvatar(profile, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(session?.title?.ifBlank { null } ?: botLabel, style = MaterialTheme.typography.titleMedium, color = BobColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(botLabel, style = MaterialTheme.typography.labelSmall, color = color)
                                Text(" · ", color = BobColors.TextFaint, style = MaterialTheme.typography.labelSmall)
                                Text(
                                    when {
                                        ui.socket != SocketState.CONNECTED -> "reconnecting…"
                                        session?.status == "starting" -> "starting up…"
                                        session?.isBusy == true -> "working…"
                                        session?.status == "waiting" -> "needs your input"
                                        else -> "online"
                                    },
                                    style = MaterialTheme.typography.labelSmall, color = BobColors.TextMuted,
                                )
                            }
                        }
                    }
                },
                actions = {
                    ModelChip(model = session?.model ?: "", effort = session?.reasoningEffort ?: "", onModel = vm::openModelPicker, onReasoning = vm::openReasoningPicker)
                },
            )
        },
        bottomBar = {
            Composer(
                input = ui.input, onInput = vm::setInput, onSend = vm::send, onStop = vm::interrupt,
                busy = session?.isBusy == true, attachments = session?.attachments ?: emptyList(),
                attaching = ui.attaching, onAttach = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = ui.liveId != null && ui.socket == SocketState.CONNECTED,
                statusLine = session?.statusLine, notice = session?.notice, botColor = color,
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                ui.connecting -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = BobColors.Accent)
                    Spacer(Modifier.height(12.dp))
                    Text("Opening chat with $botLabel…", color = BobColors.TextMuted)
                }
                ui.error != null && ui.liveId == null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't open this chat", style = MaterialTheme.typography.titleMedium, color = BobColors.Text)
                    Spacer(Modifier.height(6.dp))
                    Text(ui.error ?: "", color = BobColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { vm.retry(sessionId) }) { Text("Try again") }
                }
                items.isEmpty() -> EmptyChat(profile, ui.bot?.description, ui.bot?.model ?: session?.model ?: "")
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                    items(items, key = { it.id }) { item -> MessageItem(item, profile) }
                    item { Spacer(Modifier.height(4.dp)) }
                }
            }
        }
    }

    // ---- blocking prompts ----
    session?.approval?.let { a ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = BobColors.SurfaceRaised,
            title = { Text("$botLabel wants to run a command") },
            text = {
                Column {
                    if (a.description.isNotBlank()) { Text(a.description, color = BobColors.TextMuted); Spacer(Modifier.height(8.dp)) }
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(BobColors.Bg).padding(12.dp)) {
                        Text(a.command, color = BobColors.Text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if ("once" in a.choices) Button(onClick = { vm.approve("once") }, colors = ButtonDefaults.buttonColors(containerColor = BobColors.Accent, contentColor = BobColors.Bg)) { Text("Allow once") }
                    if ("session" in a.choices) TextButton(onClick = { vm.approve("session") }) { Text("This chat") }
                    if ("always" in a.choices) TextButton(onClick = { vm.approve("always") }) { Text("Always") }
                }
            },
            dismissButton = { TextButton(onClick = { vm.approve("deny") }) { Text("Deny", color = BobColors.Rose) } },
        )
    }
    session?.clarify?.let { c ->
        var custom by remember(c.requestId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {},
            containerColor = BobColors.SurfaceRaised,
            title = { Text("$botLabel is asking") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(c.question, color = BobColors.Text)
                    c.choices.forEach { ch -> OutlinedButton(onClick = { vm.clarify(c.requestId, ch) }, modifier = Modifier.fillMaxWidth()) { Text(ch) } }
                    OutlinedTextField(value = custom, onValueChange = { custom = it }, placeholder = { Text("Or type an answer") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { if (custom.isNotBlank()) vm.clarify(c.requestId, custom) }) { Text("Answer") } },
        )
    }
    session?.secret?.let { s ->
        var v by remember(s.requestId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {},
            containerColor = BobColors.SurfaceRaised,
            title = { Text(if (s.kind == "sudo") "Sudo password needed" else "Secret needed") },
            text = { Column { Text(s.prompt, color = BobColors.TextMuted); Spacer(Modifier.height(8.dp)); OutlinedTextField(value = v, onValueChange = { v = it }, singleLine = true, colors = fieldColors(), visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()) } },
            confirmButton = { TextButton(onClick = { vm.secret(s.requestId, s.kind, v) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { vm.interrupt() }) { Text("Cancel", color = BobColors.Rose) } },
        )
    }

    if (ui.showModelPicker) {
        ModelPickerSheet(
            catalog = ui.catalog, current = session?.model ?: "",
            onDismiss = vm::closeModelPicker,
            onPick = { p, m -> vm.pickModel(p, m) },
            onPickDefault = { p, m -> vm.pickModel(p, m, makeDefault = true) },
        )
    }
    if (ui.showReasoningPicker) {
        ModalBottomSheet(onDismissRequest = vm::closeReasoningPicker, containerColor = BobColors.SurfaceRaised) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).navigationBarsPadding()) {
                Text("Reasoning effort", style = MaterialTheme.typography.titleMedium, color = BobColors.Text)
                Text("Applies to this chat.", style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted)
                Spacer(Modifier.height(12.dp))
                listOf("none", "minimal", "low", "medium", "high", "xhigh", "max").forEach { lvl ->
                    val sel = lvl == (session?.reasoningEffort ?: "")
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(if (sel) BobColors.AccentSoft else BobColors.Surface).clickable { vm.pickReasoning(lvl) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Psychology, null, tint = if (sel) BobColors.Accent else BobColors.TextMuted)
                        Spacer(Modifier.width(12.dp))
                        Text(lvl, color = BobColors.Text, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (renaming) {
        var t by remember { mutableStateOf(session?.title ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = BobColors.SurfaceRaised,
            title = { Text("Rename chat") },
            text = { OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true, colors = fieldColors()) },
            confirmButton = { TextButton(onClick = { renaming = false; vm.rename(t) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ModelChip(model: String, effort: String, onModel: () -> Unit, onReasoning: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        if (effort.isNotBlank() && effort != "none") {
            Pill(effort, color = BobColors.Violet, icon = Icons.Outlined.Psychology, onClick = onReasoning)
            Spacer(Modifier.width(6.dp))
        } else {
            IconButton(onClick = onReasoning, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Psychology, "Reasoning", tint = BobColors.TextMuted, modifier = Modifier.size(18.dp)) }
        }
        Row(
            Modifier.clip(CircleShape).background(BobColors.SurfaceRaised).border(1.dp, BobColors.OutlineSoft, CircleShape).clickable(onClick = onModel).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(model.substringAfterLast('/').ifBlank { "model" }, style = MaterialTheme.typography.labelMedium, color = BobColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
            Icon(Icons.Rounded.ExpandMore, null, tint = BobColors.TextMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EmptyChat(profile: String, description: String?, model: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        BotAvatar(profile, 72.dp)
        Spacer(Modifier.height(16.dp))
        Text(botName(profile), style = MaterialTheme.typography.headlineSmall, color = BobColors.Text)
        if (!description.isNullOrBlank()) { Spacer(Modifier.height(6.dp)); Text(description, color = BobColors.TextMuted, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        if (model.isNotBlank()) { Spacer(Modifier.height(10.dp)); Pill(model.substringAfterLast('/'), color = BobColors.TextMuted) }
        Spacer(Modifier.height(24.dp))
        Text("Say something to get started. Tap the model chip to switch models for this chat.", color = BobColors.TextFaint, style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun Composer(
    input: String, onInput: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit,
    busy: Boolean, attachments: List<String>, attaching: Boolean, onAttach: () -> Unit, enabled: Boolean,
    statusLine: String?, notice: String?, botColor: androidx.compose.ui.graphics.Color,
) {
    Column(Modifier.fillMaxWidth().background(BobColors.Bg).imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (!notice.isNullOrBlank()) {
            BobCard(container = BobColors.AmberSoft, border = BobColors.Amber.copy(alpha = 0.4f), padding = PaddingValues(10.dp)) { Text(notice, color = BobColors.Amber, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(6.dp))
        }
        if (!statusLine.isNullOrBlank() || busy) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = botColor)
                Spacer(Modifier.width(8.dp))
                Text(statusLine ?: "Thinking…", style = MaterialTheme.typography.labelMedium, color = BobColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (attachments.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                attachments.forEach { Pill(it, color = BobColors.Mint, icon = Icons.Outlined.AddPhotoAlternate) }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(BobColors.SurfaceRaised).border(1.dp, BobColors.OutlineSoft, RoundedCornerShape(26.dp)).padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = onAttach, enabled = enabled && !attaching) {
                if (attaching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BobColors.Accent)
                else Icon(Icons.Outlined.AddPhotoAlternate, "Attach image", tint = BobColors.TextMuted)
            }
            OutlinedTextField(
                value = input, onValueChange = onInput, enabled = enabled,
                placeholder = { Text(if (enabled) "Message…" else "Connecting…", color = BobColors.TextFaint) },
                colors = fieldColors().let { it }.run {
                    androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        disabledBorderColor = androidx.compose.ui.graphics.Color.Transparent, cursorColor = BobColors.Accent,
                        focusedTextColor = BobColors.Text, unfocusedTextColor = BobColors.Text, disabledTextColor = BobColors.TextMuted,
                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    )
                },
                maxLines = 6,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                FilledIconButton(onClick = onStop, colors = IconButtonDefaults.filledIconButtonColors(containerColor = BobColors.RoseSoft, contentColor = BobColors.Rose), modifier = Modifier.padding(bottom = 4.dp)) { Icon(Icons.Outlined.Stop, "Stop") }
            } else {
                FilledIconButton(onClick = onSend, enabled = enabled && (input.isNotBlank() || attachments.isNotEmpty()), colors = IconButtonDefaults.filledIconButtonColors(containerColor = BobColors.Accent, contentColor = BobColors.Bg, disabledContainerColor = BobColors.SurfaceHigh, disabledContentColor = BobColors.TextFaint), modifier = Modifier.padding(bottom = 4.dp)) { Icon(Icons.Rounded.ArrowUpward, "Send") }
            }
        }
    }
}
