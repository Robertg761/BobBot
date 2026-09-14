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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
    onOpenNetwork: () -> Unit = {},
    onOpenTeam: () -> Unit = {},
    vm: ChatViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(sessionId, profile) { vm.open(sessionId, profile, mainConversation) }
    DisposableEffect(Unit) { onDispose { vm.leave() } }
    com.bobbot.ui.components.PollWhileStarted(Unit, 15_000, immediate = false) { vm.loadPermissions() }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(ui.toastSeq) { ui.toast?.let { snack.showSnackbar(it); vm.clearToast() } }
    val listState = rememberLazyListState()
    val session = ui.session
    val items = session?.items ?: emptyList()
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    /** The bubble a long-press opened the actions sheet for. */
    var actionsFor by remember { mutableStateOf<ChatItem?>(null) }

    val color = botColor(profile)
    val botLabel = botName(profile)
    val isMain = mainConversation || ui.isMain
    val title = if (isMain) botLabel else session?.title?.ifBlank { null } ?: botLabel

    // Working steps are folded into one row each, so the list is built from entries, not raw items.
    val entries = remember(items) { groupChatItems(items) }
    val lastAssistant = items.lastOrNull() as? ChatItem.Assistant
    val showTyping = session?.isBusy == true && !(lastAssistant != null && lastAssistant.streaming && lastAssistant.text.isNotBlank())
    val prompt = session?.approval != null || session?.clarify != null || session?.secret != null
    val extra = (if (showTyping) 1 else 0) + (if (prompt) 1 else 0) + 1
    val lastIndex = (if (items.isEmpty()) 1 else 0) + entries.size + extra - 1

    // Follow the conversation like a messages app: animate once when something new arrives, snap
    // without animation while a reply streams, and stop following as soon as the user scrolls up.
    val follow = rememberFollowBottom(listState)
    // Markdown lays out a beat after the item appears, so aim past the end; the list clamps to the real bottom.
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(items.size, showTyping, prompt) {
        if (items.lastOrNull() is ChatItem.User) follow.value = true
        if (!follow.value || lastIndex < 0) return@LaunchedEffect
        // First load snaps; later arrivals animate. Either way, long markdown bubbles finish measuring a
        // frame or two later and push the end down, so settle again once they have.
        if (!opened) { opened = true; listState.scrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE / 2) }
        else listState.animateScrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE / 2)
        repeat(3) {
            androidx.compose.runtime.withFrameNanos { }
            if (follow.value && listState.canScrollForward) listState.scrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE / 2)
        }
    }
    LaunchedEffect(lastAssistant?.text?.length) {
        if (follow.value && lastAssistant?.streaming == true && lastIndex >= 0) listState.scrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE / 2)
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
                            // Only what is happening right now goes under the name; the model lives in the ⋮ menu.
                            val status = when {
                                ui.socket != SocketState.CONNECTED -> "Reconnecting…" to BobColors.TextFaint
                                session?.status == "starting" -> "Starting up…" to BobColors.TextMuted
                                session?.status == "waiting" -> "Needs your input" to BobColors.Rose
                                session?.isBusy == true -> (session.statusLine?.takeIf { it.isNotBlank() } ?: "Working…") to BobColors.Amber
                                // A task chat is titled after the task, so say whose chat it is; the ongoing chat already is the bot.
                                !isMain -> botLabel to color
                                else -> null
                            }
                            if (status != null) Text(status.first, style = MaterialTheme.typography.labelSmall, color = status.second, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                enabled = ui.liveId != null, canSend = ui.socket == SocketState.CONNECTED,
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
                else -> Column(Modifier.fillMaxSize()) {
                    if (ui.tasks.isNotEmpty()) TaskStrip(ui.tasks, profile, onOpenNetwork)
                    if (ui.permissions.isNotEmpty()) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ui.permissions.take(3).forEach { r ->
                                com.bobbot.ui.team.PermissionCard(r, ui.authority, busy = false, onDecide = { c, sc -> vm.decidePermission(r.id, c, sc) }, currentSession = session?.storedId)
                            }
                        }
                    }
                    if (profile == ui.authority && (ui.waitingForAuthority > 0 || ui.waitingForYou > 0)) {
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = onOpenTeam).padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.bobbot.ui.team.PermissionSummaryLine(ui.waitingForYou, ui.waitingForAuthority, ui.authority, color = if (ui.waitingForYou > 0) BobColors.Rose else BobColors.Amber)
                            Spacer(Modifier.weight(1f))
                            Text("Review", style = MaterialTheme.typography.labelSmall, color = BobColors.Accent)
                        }
                    }
                    LazyColumn(state = listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                    if (items.isEmpty()) item(key = "intro") { Intro(profile, ui.bot?.description) }
                    // Tool and system lines carry no time; the last timed entry decides whether a new day started.
                    var previousTimed: ChatItem? = null
                    entries.forEachIndexed { i, entry ->
                        val timedBefore = previousTimed
                        when (entry) {
                            is Entry.Run -> {
                                val live = session?.isBusy == true && i == entries.lastIndex
                                // A reply's thinking belongs to the work that led to it, not above the bubble.
                                val reasoningAfter = ((entries.getOrNull(i + 1) as? Entry.Single)?.item as? ChatItem.Assistant)
                                    ?.takeIf { it.text.isNotBlank() }?.reasoning.orEmpty()
                                item(key = entry.key) {
                                    Spacer(Modifier.height(if (i == 0) 0.dp else gapBetween(false)))
                                    WorkRow(entry, profile, live, trailingReasoning = reasoningAfter)
                                }
                            }
                            is Entry.Single -> {
                                val msg = entry.item
                                val above = (entries.getOrNull(i - 1) as? Entry.Single)?.item
                                val below = (entries.getOrNull(i + 1) as? Entry.Single)?.item
                                val groupedAbove = sameSender(above, msg)
                                val groupedBelow = sameSender(msg, below)
                                item(key = msg.id) {
                                    val at = msg.at
                                    if (at != null && startsNewDay(timedBefore, msg)) DaySeparator(dayLabel(at))
                                    else Spacer(Modifier.height(if (i == 0) 0.dp else gapBetween(groupedAbove)))
                                    val afterRun = entries.getOrNull(i - 1) is Entry.Run
                                    MessageItem(msg, profile, groupedAbove, groupedBelow, onLongPress = { if (messageText(it).isNotBlank()) actionsFor = it }, hideReasoning = afterRun && msg is ChatItem.Assistant && msg.text.isNotBlank())
                                    val settled = msg is ChatItem.User || msg is ChatItem.Teammate || (msg is ChatItem.Assistant && !msg.streaming && msg.text.isNotBlank())
                                    if (at != null && !groupedBelow && settled) TimeLabel(at, mine = msg is ChatItem.User)
                                }
                            }
                        }
                        previousTimed = entry.items.lastOrNull { it.at != null } ?: previousTimed
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
    actionsFor?.let { target ->
        MessageActionsSheet(
            item = target,
            onDismiss = { actionsFor = null },
            onCopied = { vm.toast("Copied") },
            onSendAgain = vm::resend,
        )
    }
    if (renaming) {
        var t by remember(session?.title) { mutableStateOf(session?.title ?: "") }
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

/**
 * Whether the list should keep pinning to the newest entry. Starts true; a drag by the user turns it
 * off unless the drag ends at the bottom, so reading back through history is never yanked away.
 */
@Composable
fun rememberFollowBottom(listState: androidx.compose.foundation.lazy.LazyListState): androidx.compose.runtime.MutableState<Boolean> {
    val follow = remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> follow.value = false
                is androidx.compose.foundation.interaction.DragInteraction.Stop,
                is androidx.compose.foundation.interaction.DragInteraction.Cancel -> if (!listState.canScrollForward) follow.value = true
            }
        }
    }
    LaunchedEffect(listState) {
        // A fling that lands at the bottom also resumes following.
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (moving, canForward) -> if (!moving && !canForward) follow.value = true }
    }
    return follow
}

/** Two adjacent entries "belong together" when the same side sent both bubbles. */
private fun sameSender(a: ChatItem?, b: ChatItem?): Boolean = when {
    a == null || b == null -> false
    a is ChatItem.User && b is ChatItem.User -> true
    a is ChatItem.Assistant && b is ChatItem.Assistant -> true
    a is ChatItem.Teammate && b is ChatItem.Teammate -> a.profile == b.profile && a.reply == b.reply
    else -> false
}

/** The board work behind this conversation: what this bot is doing or has handed out. Tap for the network. */
@Composable
private fun TaskStrip(tasks: List<com.bobbot.data.model.BoardTask>, profile: String, onOpen: () -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(tasks.size, key = { tasks[it].id }) { i ->
            val t = tasks[i]
            val other = if (t.assignee == profile) t.createdBy else t.assignee
            val label = buildString {
                if (t.assignee == profile) append("For ${botLabelOf(other)}: ") else append("${botLabelOf(other)}: ")
                append(t.title.take(40))
            }
            val color = when (t.status.lowercase()) {
                "in_progress", "running", "doing" -> BobColors.Amber
                "review", "in_review" -> BobColors.Violet
                "blocked", "waiting" -> BobColors.Rose
                else -> BobColors.TextMuted
            }
            com.bobbot.ui.components.Pill(label, color = color, onClick = onOpen)
        }
    }
}

private fun botLabelOf(profile: String?): String = profile?.takeIf { it.isNotBlank() }?.let { com.bobbot.data.repo.BotNames.display(it) } ?: "board"

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

/** The raw text behind a bubble, the thing worth copying or sending again. */
private fun messageText(item: ChatItem): String = when (item) {
    is ChatItem.User -> item.text
    is ChatItem.Assistant -> item.text
    is ChatItem.Teammate -> item.text
    else -> ""
}

/**
 * Long-press actions for one message. No delete: Hermes keeps the transcript server-side and has
 * no per-message delete, so offering one would lie.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(item: ChatItem, onDismiss: () -> Unit, onCopied: () -> Unit, onSendAgain: (String) -> Unit) {
    val ctx = LocalContext.current
    val text = messageText(item)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = BobColors.SurfaceRaised) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            SheetAction(Icons.Outlined.ContentCopy, "Copy") {
                val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clip?.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
                onCopied()
                onDismiss()
            }
            SheetAction(Icons.Outlined.Share, "Share") {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                }
                runCatching { ctx.startActivity(android.content.Intent.createChooser(send, null)) }
                onDismiss()
            }
            // Only your own words can be said again; attachments are not repeated.
            if (item is ChatItem.User) SheetAction(Icons.Outlined.Refresh, "Send again") { onSendAgain(text); onDismiss() }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = BobColors.TextMuted)
        Spacer(Modifier.width(16.dp))
        Text(label, color = BobColors.Text, style = MaterialTheme.typography.bodyLarge)
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
                        if ("session" in approval.choices) TextButton(onClick = { vm.approve("session") }) { Text("Allow in this chat") }
                        if ("always" in approval.choices) TextButton(onClick = { vm.approve("always") }) { Text("Always allow") }
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
    /** Typing stays possible during a socket blip; only sending waits for the connection. */
    canSend: Boolean = true,
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
                Modifier.weight(1f).clip(RoundedCornerShape(26.dp)).background(BobColors.SurfaceRaised).padding(start = 6.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input, onValueChange = onInput, enabled = enabled,
                    placeholder = { Text(if (enabled) placeholder else "Opening…", color = BobColors.TextFaint) },
                    // Sentence capitalisation and a plain text keyboard: without this the keyboard gets no hint and stays lowercase.
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                        autoCorrectEnabled = true,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent,
                        cursorColor = BobColors.Accent, focusedTextColor = BobColors.Text, unfocusedTextColor = BobColors.Text, disabledTextColor = BobColors.TextMuted,
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    ),
                    maxLines = 6,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                if (busy) {
                    FilledIconButton(onClick = onStop, colors = IconButtonDefaults.filledIconButtonColors(containerColor = BobColors.RoseSoft, contentColor = BobColors.Rose), modifier = Modifier.padding(bottom = 6.dp).size(44.dp)) { Icon(Icons.Outlined.Stop, "Stop") }
                } else {
                    val sendable = enabled && canSend && (input.isNotBlank() || attachments.isNotEmpty())
                    FilledIconButton(
                        onClick = onSend, enabled = sendable,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = BobColors.UserBubble, contentColor = BobColors.UserBubbleText, disabledContainerColor = BobColors.SurfaceHigh, disabledContentColor = BobColors.TextFaint),
                        modifier = Modifier.padding(bottom = 6.dp).size(44.dp),
                    ) { Icon(Icons.Rounded.ArrowUpward, "Send") }
                }
            }
        }
    }
}
