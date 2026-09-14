package com.bobbot.ui.board

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Task
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobbot.data.model.BoardTask
import com.bobbot.data.repo.BoardActivity
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.components.EmptyState
import com.bobbot.ui.components.LoadingRow
import com.bobbot.ui.components.Pill
import com.bobbot.ui.components.SectionHeader
import com.bobbot.data.repo.botName
import com.bobbot.ui.theme.BobColors
import com.bobbot.ui.theme.botColor

private const val SYSTEM = "system"

private val StatusOrder = listOf("triage", "todo", "ready", "running", "review", "done", "blocked")

private fun statusLabel(status: String): String = when (status.lowercase()) {
    "todo" -> "To do"
    "running", "in_progress", "in-progress", "doing" -> "In progress"
    "done" -> "Done"
    "blocked" -> "Blocked"
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
}

private fun statusColor(status: String): Color = when (status.lowercase()) {
    "todo" -> BobColors.TextMuted
    "running", "in_progress", "in-progress", "doing" -> BobColors.Accent
    "done" -> BobColors.Mint
    "blocked" -> BobColors.Rose
    else -> BobColors.Violet
}

private fun kindColor(kind: String): Color = when (kind.lowercase()) {
    "assigned" -> BobColors.Accent
    "commented" -> BobColors.Violet
    "completed" -> BobColors.Mint
    "blocked" -> BobColors.Rose
    "spawned" -> BobColors.Amber
    else -> BobColors.TextMuted
}

private fun priorityColor(priority: String?): Color = when (priority?.lowercase()) {
    "high", "urgent", "p0", "p1" -> BobColors.Rose
    "medium", "normal", "p2" -> BobColors.Amber
    "low", "p3" -> BobColors.TextMuted
    else -> BobColors.TextMuted
}

/**
 * The board tab: bot-to-bot traffic made obvious. "Activity" is the cross-task feed of who
 * asked whom for what; "Tasks" is the kanban itself, grouped by column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(onOpenTeam: () -> Unit, onOpenRelay: () -> Unit, onChat: (profile: String) -> Unit) {
    val vm: BoardViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var askOpen by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        containerColor = BobColors.Bg,
        topBar = {
            TopAppBar(
                title = { Text("Bot network") },
                actions = {
                    TextButton(onClick = onOpenTeam) { Text("Permissions") }
                    IconButton(onClick = { vm.refresh() }) {
                        if (ui.refreshing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BobColors.Accent)
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = BobColors.TextMuted)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BobColors.Bg),
            )
        },
        floatingActionButton = {
            if (ui.available != false) {
                ExtendedFloatingActionButton(
                    text = { Text("Ask a bot") },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    onClick = { askOpen = true },
                    containerColor = BobColors.Accent,
                    contentColor = BobColors.Bg,
                )
            }
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("hero") { RelayHeroCard(onOpenRelay) }

            if (ui.available == false) {
                item("unavailable") {
                    BobCard {
                        EmptyState(
                            title = "The kanban plugin is off",
                            subtitle = "Hermes runs bot-to-bot work through its kanban plugin, and this server does not have it enabled. " +
                                "Turn on the kanban plugin to see tasks and activity here. Group conversations are available separately.",
                            icon = Icons.Outlined.CloudOff,
                        )
                    }
                }
                return@LazyColumn
            }

            item("tabs") {
                SegmentedControl(
                    options = listOf("Activity", "Tasks"),
                    selected = tab,
                    onSelect = { tab = it },
                )
            }

            ui.error?.let { message ->
                item("error") {
                    BobCard(container = BobColors.RoseSoft, border = BobColors.Rose.copy(alpha = 0.4f)) {
                        Text("Could not reach the board", style = MaterialTheme.typography.titleSmall, color = BobColors.Rose)
                        Spacer(Modifier.height(4.dp))
                        Text(message, style = MaterialTheme.typography.bodyMedium, color = BobColors.TextMuted)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { vm.refresh() }) { Text("Retry", color = BobColors.Accent) }
                    }
                }
            }

            if (ui.loading) {
                item("loading") { LoadingRow("Loading the board…") }
            } else if (tab == 0) {
                if (ui.activity.isEmpty()) {
                    item("no-activity") {
                        EmptyState(
                            title = "No bot chatter yet",
                            subtitle = "When one bot assigns work to another or comments on a task, it shows up here.",
                            icon = Icons.Outlined.Forum,
                        )
                    }
                } else {
                    items(ui.activity) { activity ->
                        ActivityRow(
                            item = activity,
                            onClick = {
                                ui.tasks.firstOrNull { it.id == activity.taskId }?.let(vm::openTask)
                            },
                        )
                    }
                }
            } else {
                if (ui.tasks.isEmpty()) {
                    item("no-tasks") {
                        EmptyState(
                            title = "The board is empty",
                            subtitle = "Use “Ask a bot” to put the first task on the board.",
                            icon = Icons.Outlined.Inbox,
                        )
                    }
                } else {
                    val groups = ui.tasks.groupBy { it.status.lowercase() }
                    val ordered = StatusOrder.filter { groups.containsKey(it) } +
                        groups.keys.filterNot { it in StatusOrder }.sorted()
                    ordered.forEach { status ->
                        val tasks = groups[status].orEmpty()
                        item("h-$status") {
                            SectionHeader("${statusLabel(status)} · ${tasks.size}")
                        }
                        items(tasks) { task ->
                            TaskCard(task = task, onClick = { vm.openTask(task) })
                        }
                    }
                }
            }
        }
    }

    ui.detail?.let { detail ->
        ModalBottomSheet(
            onDismissRequest = { vm.closeTask() },
            sheetState = sheetState,
            containerColor = BobColors.Surface,
            contentColor = BobColors.Text,
        ) {
            TaskDetailSheet(
                detail = detail,
                onComment = vm::addComment,
                onStatus = vm::setStatus,
                onChat = onChat,
            )
        }
    }

    if (askOpen) {
        AskBotDialog(
            assignees = ui.assignees,
            botNames = vm.botNames(),
            sending = ui.sending,
            error = ui.sendError,
            onDismiss = { askOpen = false; vm.clearSendError() },
            onSend = { from, to, title, body ->
                vm.sendTask(from, to, title, body) { askOpen = false }
            },
        )
    }
}

@Composable
private fun RelayHeroCard(onOpenRelay: () -> Unit) {
    BobCard(container = BobColors.SurfaceRaised, border = BobColors.Accent.copy(alpha = 0.28f)) {
        Text(
            "Start a bot-to-bot conversation",
            style = MaterialTheme.typography.titleMedium,
            color = BobColors.Text,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Choose two to six bots and give them a topic. Conversations continue on Hermes when your phone disconnects.",
            style = MaterialTheme.typography.bodyMedium,
            color = BobColors.TextMuted,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onOpenRelay,
            colors = ButtonDefaults.buttonColors(containerColor = BobColors.Accent, contentColor = BobColors.Bg),
            shape = MaterialTheme.shapes.small,
        ) {
            Icon(Icons.Outlined.Forum, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open group chats")
        }
    }
}

@Composable
private fun FromToLine(from: String, to: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            botName(from),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = botColor(from),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("  →  ", style = MaterialTheme.typography.titleSmall, color = BobColors.TextFaint)
        Text(
            botName(to),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = botColor(to),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActivityRow(item: BoardActivity, onClick: () -> Unit) {
    val from = item.from ?: SYSTEM
    val to = item.to ?: SYSTEM
    BobCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(from, size = 28.dp)
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = "to",
                tint = BobColors.TextFaint,
                modifier = Modifier.padding(horizontal = 6.dp).size(16.dp),
            )
            BotAvatar(to, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                FromToLine(from, to)
                val stamp = relativeTime(item.at)
                if (stamp.isNotEmpty()) {
                    Text(stamp, style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint)
                }
            }
            Pill(item.kind, color = kindColor(item.kind))
        }
        if (item.text.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                item.text.trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = BobColors.Text,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Task, contentDescription = null, tint = BobColors.TextFaint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                item.taskTitle,
                style = MaterialTheme.typography.labelMedium,
                color = BobColors.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TaskCard(task: BoardTask, onClick: () -> Unit) {
    BobCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                task.title,
                style = MaterialTheme.typography.titleSmall,
                color = BobColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            task.priority?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.width(8.dp))
                Pill(it, color = priorityColor(it))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val assignee = task.assignee ?: SYSTEM
            BotAvatar(assignee, size = 24.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                assignee,
                style = MaterialTheme.typography.labelLarge,
                color = botColor(assignee),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "from ${task.createdBy ?: SYSTEM}",
                style = MaterialTheme.typography.labelSmall,
                color = BobColors.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TaskDetailSheet(
    detail: TaskDetailState,
    onComment: (String) -> Unit,
    onStatus: (String) -> Unit,
    onChat: (String) -> Unit,
) {
    val task = detail.task
    var draft by rememberSaveable(task.id) { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(task.title, style = MaterialTheme.typography.titleLarge, color = BobColors.Text)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val from = task.createdBy ?: SYSTEM
            val to = task.assignee ?: SYSTEM
            BotAvatar(from, size = 26.dp)
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = "to",
                tint = BobColors.TextFaint,
                modifier = Modifier.padding(horizontal = 6.dp).size(16.dp),
            )
            BotAvatar(to, size = 26.dp)
            Spacer(Modifier.width(10.dp))
            FromToLine(from, to, Modifier.weight(1f))
            Pill(statusLabel(task.status), color = statusColor(task.status))
        }
        val created = relativeTime(task.createdAt)
        if (created.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Created $created", style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint)
        }

        if (task.description.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            BobCard(container = BobColors.SurfaceRaised) {
                MarkdownBody(task.description)
            }
        }

        task.assignee?.takeIf { it.isNotBlank() }?.let { assignee ->
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onChat(assignee) }) {
                Text("Open a chat with $assignee", color = BobColors.Accent)
            }
        }

        Spacer(Modifier.height(10.dp))
        SectionHeader("Move to")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            StatusOrder.forEach { status ->
                val selected = task.status.lowercase() == status
                Pill(
                    text = statusLabel(status),
                    color = if (selected) BobColors.Bg else statusColor(status),
                    container = if (selected) statusColor(status) else statusColor(status).copy(alpha = 0.14f),
                    onClick = { if (!detail.busy) onStatus(status) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("Thread · ${detail.comments.size}")
        when {
            detail.loading -> LoadingRow("Loading the thread…")
            detail.comments.isEmpty() -> Text(
                "No comments yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = BobColors.TextFaint,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                detail.comments.forEach { comment ->
                    Row(Modifier.fillMaxWidth()) {
                        BotAvatar(comment.author, size = 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    comment.author,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = botColor(comment.author),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    relativeTime(comment.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BobColors.TextFaint,
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            MarkdownBody(comment.body)
                        }
                    }
                }
            }
        }

        detail.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = BobColors.Rose)
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BobTextField(
                value = draft,
                onValueChange = { draft = it },
                label = "Comment as you",
                placeholder = "Say something on this thread…",
                minLines = 1,
                maxLines = 4,
                enabled = !detail.busy,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { onComment(draft); draft = "" },
                enabled = draft.isNotBlank() && !detail.busy,
            ) {
                if (detail.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BobColors.Accent)
                } else {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "Post comment",
                        tint = if (draft.isNotBlank()) BobColors.Accent else BobColors.TextFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun AskBotDialog(
    assignees: List<String>,
    botNames: List<String>,
    sending: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSend: (from: String?, to: String, title: String, body: String) -> Unit,
) {
    val recipients = remember(assignees, botNames) {
        (assignees + botNames).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
    var to by rememberSaveable { mutableStateOf<String?>(null) }
    var from by rememberSaveable { mutableStateOf("you") }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    val senders = listOf("you") + botNames

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        containerColor = BobColors.SurfaceRaised,
        titleContentColor = BobColors.Text,
        textContentColor = BobColors.TextMuted,
        title = { Text("Ask a bot") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (recipients.isEmpty()) {
                    Text("No bots are available on the board yet.", color = BobColors.TextFaint)
                }
                PickerField(
                    label = "Send to",
                    value = to,
                    options = recipients,
                    onSelect = { to = it },
                    placeholder = "Pick a bot",
                    leading = { BotAvatar(it, size = 22.dp) },
                )
                PickerField(
                    label = "On behalf of",
                    value = from,
                    options = senders,
                    onSelect = { from = it },
                    leading = { if (it != "you") BotAvatar(it, size = 22.dp) },
                )
                BobTextField(value = title, onValueChange = { title = it }, label = "Title")
                BobTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = "Details",
                    minLines = 3,
                    maxLines = 8,
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BobColors.Rose) }
            }
        },
        confirmButton = {
            Button(
                onClick = { to?.let { onSend(from, it, title, body) } },
                enabled = !sending && !to.isNullOrBlank() && title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = BobColors.Accent, contentColor = BobColors.Bg),
            ) {
                if (sending) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BobColors.Bg)
                } else {
                    Text("Send")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel", color = BobColors.TextMuted) }
        },
    )
}
