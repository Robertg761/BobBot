package com.bobbot.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobbot.core.net.SocketState
import com.bobbot.data.repo.ChatItem
import com.bobbot.data.repo.ChatSessionState
import com.bobbot.data.repo.PendingApproval
import com.bobbot.data.repo.PendingClarify
import com.bobbot.data.repo.PendingSecret
import com.bobbot.data.repo.botName
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.models.ModelPickerSheet
import com.bobbot.ui.setup.fieldColors
import com.bobbot.ui.theme.BobColors
import com.bobbot.ui.theme.botColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    profile: String,
    mainConversation: Boolean = false,
    onBack: () -> Unit,
    onOpenProfile: (profile: String) -> Unit,
    onNewTaskChat: (profile: String) -> Unit,
    vm: ChatViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(sessionId, profile) { vm.open(sessionId, profile, mainConversation) }
    DisposableEffect(Unit) { onDispose { vm.leave() } }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(ui.toast) { ui.toast?.let { snack.showSnackbar(it); vm.clearToast() } }
    val listState = rememberLazyListState()
    val session = ui.session
    val items = session?.items ?: emptyList()
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val color = botColor(profile)
    val botLabel = botName(profile)
    val isMain = mainConversation || ui.isMain
    val title = if (isMain) botLabel else session?.title?.ifBlank { null } ?: botLabel

    val lastAssistant = items.lastOrNull() as? ChatItem.Assistant
    val showTyping = session?.isBusy == true && !(lastAssistant != null && lastAssistant.streaming && lastAssistant.text.isNotBlank())
    val prompt = session?.approval != null || session?.clarify != null || session?.secret != null
    val extra = (if (showTyping) 1 else 0) + (if (prompt) 1 else 0) + 1

    // Follow the stream.
    LaunchedEffect(items.size, lastAssistant?.text?.length, showTyping, prompt) {
        if (items.isNotEmpty() || showTyping || prompt) listState.animateScrollToItem(items.size + extra)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::attach) }

    Scaffold(
        containerColor = BobColors.Bg,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BobColors.Bg),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = BobColors.Text) } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onOpenProfile(profile) }.padding(end = 8.dp, top = 2.dp, bottom = 2.dp)) {
                        BotAvatar(profile, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(title, style = MaterialTheme.typography.titleMedium, color = BobColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val (status, statusColor) = when {
                                ui.socket != SocketState.CONNECTED -> "Reconnecting…" to BobColors.TextFaint
                                session?.status == "starting" -> "Starting up…" to BobColors.TextMuted
                                session?.status == "waiting" -> "Needs your input" to BobColors.Rose
                                session?.isBusy == true -> (session.statusLine?.takeIf { it.isNotBlank() } ?: "Working…") to BobColors.Amber
                                !isMain -> botLabel to color
                                else -> (session?.model?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Online") to BobColors.TextMuted
                            }
                            Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = BobColors.TextMuted) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = BobColors.SurfaceRaised) {
                            DropdownMenuItem(
                                text = { Text("Model") }, leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                                trailingIcon = { Text(session?.model?.substringAfterLast('/')?.take(18) ?: "", style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint) },
                                onClick = { menu = false; vm.openModelPicker() },
                            )
                            DropdownMenuItem(
                                text = { Text("Reasoning") }, leadingIcon = { Icon(Icons.Outlined.Psychology, null) },
                                trailingIcon = { Text(session?.reasoningEffort?.takeIf { it.isNotBlank() } ?: "default", style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint) },
                                onClick = { menu = false; vm.openReasoningPicker() },
                            )
                            DropdownMenuItem(text = { Text("New task chat") }, leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) }, onClick = { menu = false; onNewTaskChat(profile) })
                            if (!isMain) DropdownMenuItem(text = { Text("Rename chat") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text("Bot profile") }, leadingIcon = { Icon(Icons.Outlined.Info, null) }, onClick = { menu = false; onOpenProfile(profile) })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                input = ui.input, onInput = vm::setInput, onSend = vm::send, onStop = vm::interrupt,
                busy = session?.isBusy == true, attachments = session?.attachments ?: emptyList(),
                attaching = ui.attaching, onAttach = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = ui.liveId != null && ui.socket == SocketState.CONNECTED,
                notice = session?.notice, placeholder = "Message $botLabel",
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                ui.connecting -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = BobColors.Accent)
                    Spacer(Modifier.height(12.dp))
                    Text("Opening $botLabel…", color = BobColors.TextMuted)
                }
                ui.error != null && ui.liveId == null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't open this chat", style = MaterialTheme.typography.titleMedium, color = BobColors.Text)
                    Spacer(Modifier.height(6.dp))
                    Text(ui.error ?: "", color = BobColors.TextMuted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { vm.retry(sessionId) }) { Text("Try again") }
                }
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
                    if (items.isEmpty()) item(key = "intro") { Intro(profile, ui.bot?.description) }
                    items.forEachIndexed { i, item ->
                        val above = items.getOrNull(i - 1)
                        val below = items.getOrNull(i + 1)
                        val groupedAbove = sameSender(above, item)
                        val groupedBelow = sameSender(item, below)
                        item(key = item.id) {
                            Spacer(Modifier.height(if (i == 0) 0.dp else gapBetween(groupedAbove)))
                            MessageItem(item, profile, groupedAbove, groupedBelow)
                        }
                    }
                    if (showTyping) item(key = "typing") {
                        Spacer(Modifier.height(if (items.isEmpty()) 0.dp else 12.dp))
                        TypingBubble(color = color, label = session?.statusLine)
                    }
                    if (session != null && prompt) item(key = "prompt") {
                        Spacer(Modifier.height(12.dp))
                        PromptCard(session, botLabel, color, vm)
                    }
                    item(key = "tail") { Spacer(Modifier.height(4.dp)) }
                }
            }
        }
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

/** Two adjacent entries "belong together" when the same side sent both bubbles. */
private fun sameSender(a: ChatItem?, b: ChatItem?): Boolean = when {
    a == null || b == null -> false
    a is ChatItem.User && b is ChatItem.User -> true
    a is ChatItem.Assistant && b is ChatItem.Assistant -> true
    else -> false
}

@Composable
private fun Intro(profile: String, description: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 24.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BotAvatar(profile, 84.dp)
        Spacer(Modifier.height(14.dp))
        Text(botName(profile), style = MaterialTheme.typography.headlineSmall, color = BobColors.Text)
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(description, color = BobColors.TextMuted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(10.dp))
        Text("This is the start of your conversation with ${botName(profile)}.", color = BobColors.TextFaint, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

/** Approval, clarify and secret requests arrive in the transcript, like a bot asking you something. */
@Composable
private fun PromptCard(session: ChatSessionState, botLabel: String, color: Color, vm: ChatViewModel) {
    val approval: PendingApproval? = session.approval
    val clarify: PendingClarify? = session.clarify
    val secret: PendingSecret? = session.secret
    Row(Modifier.fillMaxWidth().padding(end = 24.dp)) {
        BobCard(container = BobColors.SurfaceRaised, border = color.copy(alpha = 0.35f), padding = PaddingValues(14.dp)) {
            when {
                approval != null -> {
                    Text("$botLabel wants to run a command", style = MaterialTheme.typography.titleSmall, color = BobColors.Text)
                    if (approval.description.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(approval.description, color = BobColors.TextMuted, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(BobColors.Bg).padding(10.dp)) {
                        Text(approval.command, color = BobColors.Text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if ("once" in approval.choices) Button(onClick = { vm.approve("once") }, colors = ButtonDefaults.buttonColors(containerColor = BobColors.Accent, contentColor = BobColors.Bg), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text("Allow") }
                        if ("session" in approval.choices) TextButton(onClick = { vm.approve("session") }) { Text("This chat") }
                        if ("always" in approval.choices) TextButton(onClick = { vm.approve("always") }) { Text("Always") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.approve("deny") }) { Text("Deny", color = BobColors.Rose) }
                    }
                }
                clarify != null -> {
                    var custom by remember(clarify.requestId) { mutableStateOf("") }
                    Text("$botLabel is asking", style = MaterialTheme.typography.labelMedium, color = color)
                    Spacer(Modifier.height(4.dp))
                    Text(clarify.question, color = BobColors.Text, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    clarify.choices.forEach { ch ->
                        OutlinedButton(onClick = { vm.clarify(clarify.requestId, ch) }, modifier = Modifier.fillMaxWidth()) { Text(ch) }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = custom, onValueChange = { custom = it }, placeholder = { Text("Type an answer") }, colors = fieldColors(), singleLine = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = { if (custom.isNotBlank()) vm.clarify(clarify.requestId, custom) }, enabled = custom.isNotBlank()) { Text("Send") }
                    }
                }
                secret != null -> {
                    var v by remember(secret.requestId) { mutableStateOf("") }
                    Text(if (secret.kind == "sudo") "Sudo password needed" else "Secret needed", style = MaterialTheme.typography.titleSmall, color = BobColors.Text)
                    Spacer(Modifier.height(4.dp))
                    Text(secret.prompt, color = BobColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = v, onValueChange = { v = it }, singleLine = true, colors = fieldColors(), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Row {
                        TextButton(onClick = { vm.secret(secret.requestId, secret.kind, v) }, enabled = v.isNotBlank()) { Text("Send") }
                        TextButton(onClick = { vm.interrupt() }) { Text("Cancel", color = BobColors.Rose) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Composer(
    input: String, onInput: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit,
    busy: Boolean, attachments: List<String>, attaching: Boolean, onAttach: (() -> Unit)?, enabled: Boolean,
    notice: String?, placeholder: String,
) {
    Column(Modifier.fillMaxWidth().background(BobColors.Bg).imePadding().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
        if (!notice.isNullOrBlank()) {
            BobCard(container = BobColors.AmberSoft, border = BobColors.Amber.copy(alpha = 0.4f), padding = PaddingValues(10.dp)) { Text(notice, color = BobColors.Amber, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(6.dp))
        }
        if (attachments.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                Icon(Icons.Outlined.Image, null, tint = BobColors.Mint, modifier = Modifier.size(14.dp))
                Text(if (attachments.size == 1) "1 image attached" else "${attachments.size} images attached", style = MaterialTheme.typography.labelMedium, color = BobColors.Mint)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            if (onAttach != null) {
                IconButton(onClick = onAttach, enabled = enabled && !attaching, modifier = Modifier.padding(bottom = 2.dp)) {
                    if (attaching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BobColors.Accent)
                    else Icon(Icons.Outlined.AddPhotoAlternate, "Attach image", tint = BobColors.TextMuted)
                }
            }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(BobColors.SurfaceRaised).padding(start = 6.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input, onValueChange = onInput, enabled = enabled,
                    placeholder = { Text(if (enabled) placeholder else "Connecting…", color = BobColors.TextFaint) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent,
                        cursorColor = BobColors.Accent, focusedTextColor = BobColors.Text, unfocusedTextColor = BobColors.Text, disabledTextColor = BobColors.TextMuted,
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    ),
                    maxLines = 6,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    FilledIconButton(onClick = onStop, colors = IconButtonDefaults.filledIconButtonColors(containerColor = BobColors.RoseSoft, contentColor = BobColors.Rose), modifier = Modifier.padding(bottom = 4.dp).size(38.dp)) { Icon(Icons.Outlined.Stop, "Stop") }
                } else {
                    val canSend = enabled && (input.isNotBlank() || attachments.isNotEmpty())
                    FilledIconButton(
                        onClick = onSend, enabled = canSend,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = BobColors.UserBubble, contentColor = BobColors.UserBubbleText, disabledContainerColor = BobColors.SurfaceHigh, disabledContentColor = BobColors.TextFaint),
                        modifier = Modifier.padding(bottom = 4.dp).size(38.dp),
                    ) { Icon(Icons.Rounded.ArrowUpward, "Send") }
                }
            }
        }
    }
}
