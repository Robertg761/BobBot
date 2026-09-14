package com.bobbot.ui.automations

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobbot.data.model.CronJob
import com.bobbot.data.model.CronRun
import com.bobbot.data.repo.DeliveryTarget
import com.bobbot.ui.board.BobTextField
import com.bobbot.ui.board.MarkdownBody
import com.bobbot.ui.board.PickerField
import com.bobbot.ui.board.SegmentedControl
import com.bobbot.ui.board.absoluteTime
import com.bobbot.ui.board.relativeTime
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.components.EmptyState
import com.bobbot.ui.components.LoadingRow
import com.bobbot.ui.components.Pill
import com.bobbot.ui.components.SectionHeader
import com.bobbot.data.repo.botName
import com.bobbot.ui.theme.BobColors

private fun deliveryColor(deliver: String): Color = when (deliver.lowercase()) {
    "ntfy" -> BobColors.Mint
    "telegram" -> BobColors.Accent
    "local" -> BobColors.TextFaint
    else -> BobColors.Violet
}

private fun statusColor(status: String?): Color = when (status?.lowercase()) {
    "ok", "success", "done" -> BobColors.Mint
    "error", "failed", "failure" -> BobColors.Rose
    "running" -> BobColors.Accent
    else -> BobColors.TextFaint
}

/** Automations are Hermes cron jobs: the way a bot starts a conversation instead of waiting for one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationsScreen(onBack: () -> Unit, onChat: (profile: String) -> Unit) {
    val vm: AutomationsViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()

    var showNew by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CronJob?>(null) }
    var deleting by remember { mutableStateOf<CronJob?>(null) }
    var openRun by remember { mutableStateOf<CronRun?>(null) }
    val runSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        containerColor = BobColors.Bg,
        topBar = {
            TopAppBar(
                title = { Text("Automations") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
                actions = {
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
            ExtendedFloatingActionButton(
                text = { Text("New automation") },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = { showNew = true },
                containerColor = BobColors.Accent,
                contentColor = BobColors.Bg,
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("explainer") { ExplainerCard() }

            ui.error?.let { message ->
                item("error") {
                    BobCard(container = BobColors.RoseSoft, border = BobColors.Rose.copy(alpha = 0.4f)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium, color = BobColors.Rose)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { vm.refresh() }) { Text("Retry", color = BobColors.Accent) }
                    }
                }
            }

            ui.notice?.let { notice ->
                item("notice") {
                    BobCard(container = BobColors.MintSoft, border = BobColors.Mint.copy(alpha = 0.35f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(notice, style = MaterialTheme.typography.bodyMedium, color = BobColors.Mint, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.clearNotice() }) { Text("Dismiss", color = BobColors.TextMuted) }
                        }
                    }
                }
            }

            when {
                ui.loading -> item("loading") { LoadingRow("Loading automations…") }
                ui.jobs.isEmpty() -> item("empty") {
                    EmptyState(
                        title = "No automations yet",
                        subtitle = "Create one to have a bot check on something and message you on a schedule.",
                        icon = Icons.Outlined.Schedule,
                    )
                }
                else -> items(ui.jobs) { job ->
                    JobCard(
                        job = job,
                        expanded = ui.expanded == job.id,
                        runs = ui.runs[job.id].orEmpty(),
                        runsLoading = job.id in ui.runsLoading,
                        runsError = ui.runsError[job.id],
                        busy = job.id in ui.busy,
                        onToggle = { vm.toggleExpanded(job) },
                        onEnabled = { vm.setEnabled(job, it) },
                        onRunNow = { vm.trigger(job) },
                        onEdit = { editing = job },
                        onDelete = { deleting = job },
                        onOpenRun = { openRun = it },
                        onChat = onChat,
                    )
                }
            }
        }
    }

    openRun?.let { run ->
        ModalBottomSheet(
            onDismissRequest = { openRun = null },
            sheetState = runSheetState,
            containerColor = BobColors.Surface,
            contentColor = BobColors.Text,
        ) {
            RunSheet(run)
        }
    }

    deleting?.let { job ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = BobColors.SurfaceRaised,
            titleContentColor = BobColors.Text,
            textContentColor = BobColors.TextMuted,
            title = { Text("Delete “${job.name}”?") },
            text = { Text("The schedule and its run history go away. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { vm.delete(job); deleting = null },
                    colors = ButtonDefaults.buttonColors(containerColor = BobColors.Rose, contentColor = BobColors.Bg),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = BobColors.TextMuted) } },
        )
    }

    if (showNew) {
        AutomationFormDialog(
            heading = "New automation",
            initialName = "",
            initialPrompt = "",
            initialSchedule = ScheduleDraft(),
            initialDeliver = ui.deliveryTargets.firstOrNull { it.id == "ntfy" }?.id ?: ui.deliveryTargets.firstOrNull()?.id,
            initialProfile = ui.botNames.firstOrNull(),
            targets = ui.deliveryTargets,
            botNames = ui.botNames,
            profileEditable = true,
            saving = ui.saving,
            error = ui.saveError,
            onDismiss = { showNew = false; vm.clearSaveError() },
            onSubmit = { name, prompt, schedule, deliver, profile ->
                vm.create(name, prompt, schedule, deliver, profile) { showNew = false }
            },
        )
    }

    editing?.let { job ->
        AutomationFormDialog(
            heading = "Edit automation",
            initialName = job.name,
            initialPrompt = job.prompt,
            initialSchedule = parseSchedule(job.scheduleDisplay),
            initialDeliver = job.deliver,
            initialProfile = job.profile,
            targets = ui.deliveryTargets,
            botNames = ui.botNames,
            profileEditable = false,
            saving = ui.saving,
            error = ui.saveError,
            onDismiss = { editing = null; vm.clearSaveError() },
            onSubmit = { name, prompt, schedule, deliver, _ ->
                vm.update(job, name, prompt, schedule, deliver) { editing = null }
            },
        )
    }
}

@Composable
private fun ExplainerCard() {
    val body = buildAnnotatedString {
        append("Automations are how bots reach out to you. Deliver to ")
        withStyle(SpanStyle(color = BobColors.Mint, fontWeight = FontWeight.Bold)) { append("ntfy") }
        append(" to get a push notification on this phone.")
    }
    BobCard(container = BobColors.SurfaceRaised, border = BobColors.Mint.copy(alpha = 0.22f)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = BobColors.Mint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = BobColors.TextMuted)
        }
    }
}

@Composable
private fun JobCard(
    job: CronJob,
    expanded: Boolean,
    runs: List<CronRun>,
    runsLoading: Boolean,
    runsError: String?,
    busy: Boolean,
    onToggle: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenRun: (CronRun) -> Unit,
    onChat: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    BobCard(onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(job.profile, size = 34.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    job.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = BobColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = BobColors.TextFaint, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        job.scheduleDisplay.ifBlank { "no schedule" },
                        style = MaterialTheme.typography.labelMedium,
                        color = BobColors.TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(
                checked = job.enabled,
                onCheckedChange = { if (!busy) onEnabled(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = BobColors.Bg,
                    checkedTrackColor = BobColors.Mint,
                    uncheckedThumbColor = BobColors.TextFaint,
                    uncheckedTrackColor = BobColors.Surface,
                ),
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More", tint = BobColors.TextMuted)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Run now") },
                        leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                        onClick = { menuOpen = false; onRunNow() },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = BobColors.Rose) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = BobColors.Rose) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(job.deliver, color = deliveryColor(job.deliver))
            job.lastStatus?.takeIf { it.isNotBlank() }?.let { Pill(it, color = statusColor(it)) }
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = BobColors.Accent)
        }
        Spacer(Modifier.height(8.dp))
        Row {
            val last = relativeTime(job.lastRunAt)
            val next = relativeTime(job.nextRunAt)
            Text(
                if (last.isEmpty()) "Never run" else "Last run $last",
                style = MaterialTheme.typography.labelSmall,
                color = BobColors.TextFaint,
                modifier = Modifier.weight(1f),
            )
            if (next.isNotEmpty() && job.enabled) {
                Text("Next $next", style = MaterialTheme.typography.labelSmall, color = BobColors.TextMuted)
            }
        }
        job.lastError?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = BobColors.Rose, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        if (expanded) {
            Spacer(Modifier.height(14.dp))
            SectionHeader("Prompt")
            Text(
                job.prompt.trim().ifBlank { "(empty)" },
                style = MaterialTheme.typography.bodyMedium,
                color = BobColors.TextMuted,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { onChat(job.profile) }) {
                Text("Chat with ${botName(job.profile)}", color = BobColors.Accent)
            }

            Spacer(Modifier.height(6.dp))
            SectionHeader("Recent runs")
            when {
                runsLoading -> LoadingRow("Loading runs…")
                runsError != null -> Text(runsError, style = MaterialTheme.typography.bodySmall, color = BobColors.Rose)
                runs.isEmpty() -> Text(
                    "No runs recorded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BobColors.TextFaint,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    runs.take(10).forEach { run -> RunRow(run) { onOpenRun(run) } }
                }
            }
        }
    }
}

@Composable
private fun RunRow(run: CronRun, onClick: () -> Unit) {
    BobCard(container = BobColors.SurfaceRaised, onClick = onClick, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(run.status ?: "unknown", color = statusColor(run.status))
            Spacer(Modifier.width(10.dp))
            Text(
                relativeTime(run.startedAt),
                style = MaterialTheme.typography.labelSmall,
                color = BobColors.TextFaint,
                modifier = Modifier.weight(1f),
            )
        }
        val preview = (run.error ?: run.output).orEmpty().trim()
        if (preview.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                color = if (run.error != null) BobColors.Rose else BobColors.TextMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RunSheet(run: CronRun) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(run.status ?: "unknown", color = statusColor(run.status))
            Spacer(Modifier.width(10.dp))
            Text(absoluteTime(run.startedAt), style = MaterialTheme.typography.labelMedium, color = BobColors.TextMuted)
        }
        Spacer(Modifier.height(14.dp))
        run.error?.takeIf { it.isNotBlank() }?.let {
            BobCard(container = BobColors.RoseSoft, border = BobColors.Rose.copy(alpha = 0.4f)) {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = BobColors.Rose)
            }
            Spacer(Modifier.height(12.dp))
        }
        val output = run.output.orEmpty().trim()
        if (output.isEmpty()) {
            Text("This run produced no output.", style = MaterialTheme.typography.bodyMedium, color = BobColors.TextFaint)
        } else {
            MarkdownBody(output)
        }
    }
}

@Composable
private fun AutomationFormDialog(
    heading: String,
    initialName: String,
    initialPrompt: String,
    initialSchedule: ScheduleDraft,
    initialDeliver: String?,
    initialProfile: String?,
    targets: List<DeliveryTarget>,
    botNames: List<String>,
    profileEditable: Boolean,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (name: String, prompt: String, schedule: String, deliver: String, profile: String?) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var prompt by remember { mutableStateOf(initialPrompt) }
    var draft by remember { mutableStateOf(initialSchedule) }
    var deliver by remember { mutableStateOf(initialDeliver ?: "local") }
    var profile by remember { mutableStateOf(initialProfile) }

    val targetLabels = remember(targets) { targets.map { it.label } }
    val deliverLabel = targets.firstOrNull { it.id == deliver }?.label ?: deliver
    val valid = name.isNotBlank() && prompt.isNotBlank() && draft.isValid

    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = BobColors.Bg, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(BobColors.Bg).padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, enabled = !saving) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = BobColors.Text)
                    }
                    Text(heading, style = MaterialTheme.typography.titleLarge, color = BobColors.Text, modifier = Modifier.weight(1f))
                    Button(
                        onClick = { onSubmit(name, prompt, draft.build(), deliver, profile) },
                        enabled = valid && !saving,
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(containerColor = BobColors.Accent, contentColor = BobColors.Bg),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BobColors.Bg)
                        } else {
                            Text("Save")
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    BobTextField(value = name, onValueChange = { name = it }, label = "Name")
                    BobTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = "Prompt",
                        placeholder = "What should the bot do each time?",
                        minLines = 4,
                        maxLines = 10,
                    )

                    BobCard {
                        SectionHeader("Schedule")
                        SegmentedControl(
                            options = ScheduleMode.entries.map { it.label },
                            selected = ScheduleMode.entries.indexOf(draft.mode),
                            onSelect = { index -> draft = draft.copy(mode = ScheduleMode.entries[index]) },
                        )
                        Spacer(Modifier.height(12.dp))
                        when (draft.mode) {
                            ScheduleMode.Interval -> BobTextField(
                                value = draft.minutes,
                                onValueChange = { value -> draft = draft.copy(minutes = value.filter { it.isDigit() }.take(4)) },
                                label = "Minutes between runs",
                            )
                            ScheduleMode.Daily -> BobTextField(
                                value = draft.time,
                                onValueChange = { draft = draft.copy(time = it) },
                                label = "Time (HH:MM)",
                            )
                            ScheduleMode.Weekly -> {
                                BobTextField(
                                    value = draft.time,
                                    onValueChange = { draft = draft.copy(time = it) },
                                    label = "Time (HH:MM)",
                                )
                                Spacer(Modifier.height(12.dp))
                                PickerField(
                                    label = "Day",
                                    value = WeekdayNames.getOrNull(draft.weekday),
                                    options = WeekdayNames,
                                    onSelect = { day -> draft = draft.copy(weekday = WeekdayNames.indexOf(day).coerceAtLeast(0)) },
                                )
                            }
                            ScheduleMode.Cron -> BobTextField(
                                value = draft.cron,
                                onValueChange = { draft = draft.copy(cron = it) },
                                label = "Cron expression",
                                placeholder = "0 9 * * 1",
                            )
                            ScheduleMode.Once -> BobTextField(
                                value = draft.once,
                                onValueChange = { draft = draft.copy(once = it) },
                                label = "ISO date and time",
                                placeholder = "2026-01-31T09:00",
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(draft.preview(), style = MaterialTheme.typography.bodySmall, color = BobColors.TextFaint)
                        Text("Sends: ${draft.build()}", style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint)
                    }

                    PickerField(
                        label = "Deliver to",
                        value = deliverLabel,
                        options = targetLabels,
                        onSelect = { label -> deliver = targets.firstOrNull { it.label == label }?.id ?: label },
                        placeholder = "Pick a delivery target",
                    )
                    if (deliver == "ntfy") {
                        Text(
                            "You will get a push notification on this phone once ntfy is configured in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BobColors.Mint,
                        )
                    }

                    PickerField(
                        label = "Bot",
                        value = profile,
                        options = botNames,
                        onSelect = { profile = it },
                        enabled = profileEditable,
                        placeholder = "Pick a bot",
                        leading = { BotAvatar(it, size = 22.dp) },
                    )
                    if (!profileEditable) {
                        Text(
                            "The bot cannot be changed after an automation is created.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BobColors.TextFaint,
                        )
                    }

                    error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = BobColors.Rose) }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}
