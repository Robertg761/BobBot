package com.bobbot.ui.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobbot.data.repo.RelayMessage
import com.bobbot.ui.board.BobTextField
import com.bobbot.ui.board.MarkdownBody
import com.bobbot.ui.board.PickerField
import com.bobbot.ui.board.relativeTime
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.components.LoadingRow
import com.bobbot.ui.components.SectionHeader
import com.bobbot.ui.theme.BobColors
import com.bobbot.ui.theme.botColor
import kotlin.math.roundToInt

/**
 * A live conversation between two bots. BobBot carries each reply across, so every message is
 * explicitly labelled "from → to" and the user can interject into the middle of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayScreen(onBack: () -> Unit) {
    val vm: RelayViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val relay = ui.relay
    val botNames = ui.bots.map { it.name }

    var botA by rememberSaveable { mutableStateOf<String?>(null) }
    var botB by rememberSaveable { mutableStateOf<String?>(null) }
    var topic by rememberSaveable { mutableStateOf("") }
    var rounds by rememberSaveable { mutableIntStateOf(6) }
    var showSetup by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }

    val idle = !relay.running && relay.messages.isEmpty()
    val setupMode = idle || showSetup

    Scaffold(
        containerColor = BobColors.Bg,
        topBar = {
            TopAppBar(
                title = { Text(if (setupMode) "New relay" else "${relay.botA} ↔ ${relay.botB}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = BobColors.Text)
                    }
                },
                actions = {
                    if (relay.running) {
                        IconButton(onClick = { vm.stop() }) {
                            Icon(Icons.Outlined.Stop, contentDescription = "Stop", tint = BobColors.Rose)
                        }
                    } else if (setupMode) {
                        IconButton(onClick = { vm.loadBots() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Reload bots", tint = BobColors.TextMuted)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BobColors.Bg),
            )
        },
        bottomBar = {
            if (!setupMode) {
                InterjectBar(
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = relay.running,
                    onSend = { vm.interject(draft); draft = "" },
                )
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            if (setupMode) {
                SetupForm(
                    loading = ui.loading,
                    loadError = ui.error,
                    botNames = botNames,
                    botA = botA,
                    botB = botB,
                    topic = topic,
                    rounds = rounds,
                    canCancel = relay.messages.isNotEmpty(),
                    onBotA = { botA = it; if (botB == it) botB = null },
                    onBotB = { botB = it; if (botA == it) botA = null },
                    onTopic = { topic = it },
                    onRounds = { rounds = it },
                    onCancel = { showSetup = false },
                    onStart = {
                        val a = botA
                        val b = botB
                        if (a != null && b != null) {
                            showSetup = false
                            vm.start(a, b, topic, rounds)
                        }
                    },
                )
            } else {
                Transcript(
                    ui = ui,
                    onStop = { vm.stop() },
                    onRestart = { vm.restart() },
                    onChangeSetup = {
                        botA = relay.botA.ifBlank { botA ?: "" }.takeIf { it.isNotBlank() }
                        botB = relay.botB.ifBlank { botB ?: "" }.takeIf { it.isNotBlank() }
                        if (topic.isBlank()) topic = relay.topic
                        rounds = relay.maxRounds
                        showSetup = true
                    },
                )
            }
        }
    }
}

@Composable
private fun SetupForm(
    loading: Boolean,
    loadError: String?,
    botNames: List<String>,
    botA: String?,
    botB: String?,
    topic: String,
    rounds: Int,
    canCancel: Boolean,
    onBotA: (String) -> Unit,
    onBotB: (String) -> Unit,
    onTopic: (String) -> Unit,
    onRounds: (Int) -> Unit,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BobCard(container = BobColors.SurfaceRaised, border = BobColors.Accent.copy(alpha = 0.28f)) {
            Text("Two bots, one topic", style = MaterialTheme.typography.titleMedium, color = BobColors.Text)
            Spacer(Modifier.height(6.dp))
            Text(
                "BobBot opens a session with each bot and passes every reply to the other one. You can interject while they talk.",
                style = MaterialTheme.typography.bodyMedium,
                color = BobColors.TextMuted,
            )
        }

        if (loading && botNames.isEmpty()) {
            LoadingRow("Loading bots…")
        }

        loadError?.let {
            BobCard(container = BobColors.RoseSoft, border = BobColors.Rose.copy(alpha = 0.4f)) {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = BobColors.Rose)
            }
        }

        BobCard {
            PickerField(
                label = "Bot A",
                value = botA,
                options = botNames.filter { it != botB },
                onSelect = onBotA,
                placeholder = "Pick a bot",
                leading = { BotAvatar(it, size = 22.dp) },
            )
            Spacer(Modifier.height(12.dp))
            PickerField(
                label = "Bot B",
                value = botB,
                options = botNames.filter { it != botA },
                onSelect = onBotB,
                placeholder = "Pick a different bot",
                leading = { BotAvatar(it, size = 22.dp) },
            )
            if (botNames.size < 2) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Create a second bot first — a relay needs two different bots.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BobColors.Amber,
                )
            }
        }

        BobCard {
            SectionHeader("Opening prompt")
            BobTextField(
                value = topic,
                onValueChange = onTopic,
                label = "Topic",
                placeholder = "What should they talk about?",
                minLines = 3,
                maxLines = 8,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rounds", style = MaterialTheme.typography.labelLarge, color = BobColors.TextMuted, modifier = Modifier.weight(1f))
                Text("$rounds", style = MaterialTheme.typography.titleMedium, color = BobColors.Accent)
            }
            Slider(
                value = rounds.toFloat(),
                onValueChange = { onRounds(it.roundToInt().coerceIn(1, 12)) },
                valueRange = 1f..12f,
                steps = 10,
            )
            Text(
                "One round = each bot speaks once.",
                style = MaterialTheme.typography.bodySmall,
                color = BobColors.TextFaint,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (canCancel) {
                OutlinedButton(onClick = onCancel, shape = MaterialTheme.shapes.small) {
                    Text("Back to transcript", color = BobColors.TextMuted)
                }
            }
            Button(
                onClick = onStart,
                enabled = botA != null && botB != null && botA != botB && topic.isNotBlank(),
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(containerColor = BobColors.Accent, contentColor = BobColors.Bg),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Start")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Transcript(
    ui: RelayUiState,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onChangeSetup: () -> Unit,
) {
    val relay = ui.relay
    val listState = rememberLazyListState()
    LaunchedEffect(relay.messages.size) {
        if (relay.messages.isNotEmpty()) {
            listState.animateScrollToItem(relay.messages.size - 1 + HEADER_ITEMS)
        }
    }

    Column(Modifier.fillMaxSize()) {
        RelayStatusBar(
            round = relay.round,
            maxRounds = relay.maxRounds,
            speaking = relay.speaking,
            running = relay.running,
            onStop = onStop,
            onRestart = onRestart,
            onChangeSetup = onChangeSetup,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("topic") {
                BobCard(container = BobColors.Surface) {
                    Text("TOPIC", style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint)
                    Spacer(Modifier.height(4.dp))
                    Text(relay.topic.ifBlank { "(no topic)" }, style = MaterialTheme.typography.bodyMedium, color = BobColors.TextMuted)
                }
            }
            relay.error?.let { message ->
                item("error") {
                    BobCard(container = BobColors.RoseSoft, border = BobColors.Rose.copy(alpha = 0.4f)) {
                        Text("The relay stopped", style = MaterialTheme.typography.titleSmall, color = BobColors.Rose)
                        Spacer(Modifier.height(4.dp))
                        Text(message, style = MaterialTheme.typography.bodyMedium, color = BobColors.TextMuted)
                    }
                }
            }
            items(relay.messages, key = { it.id }) { message ->
                if (message.kind == "user") {
                    InterjectionBubble(message)
                } else {
                    RelayBubble(message = message, alignEnd = message.from == relay.botB)
                }
            }
            if (relay.running && relay.speaking != null) {
                item("thinking") { ThinkingRow(relay.speaking!!) }
            }
        }
    }
}

/** Topic card (+ optional error card) sit above the messages in the list. */
private const val HEADER_ITEMS = 1

@Composable
private fun RelayStatusBar(
    round: Int,
    maxRounds: Int,
    speaking: String?,
    running: Boolean,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onChangeSetup: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(BobColors.Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Round ${round.coerceAtLeast(1)} / $maxRounds",
                style = MaterialTheme.typography.titleSmall,
                color = BobColors.Text,
                modifier = Modifier.weight(1f),
            )
            if (running) {
                OutlinedButton(onClick = onStop, shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Outlined.Stop, contentDescription = null, tint = BobColors.Rose, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop", color = BobColors.Rose)
                }
            } else {
                Button(
                    onClick = onRestart,
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(containerColor = BobColors.Accent, contentColor = BobColors.Bg),
                ) { Text("New conversation") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onChangeSetup, shape = MaterialTheme.shapes.small) {
                    Text("Change", color = BobColors.TextMuted)
                }
            }
        }
        if (running) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = botColor(speaking ?: ""))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (speaking != null) "$speaking is thinking…" else "Connecting…",
                    style = MaterialTheme.typography.bodySmall,
                    color = BobColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun ThinkingRow(speaking: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        BotAvatar(speaking, size = 24.dp)
        Spacer(Modifier.width(8.dp))
        Text("$speaking is thinking…", style = MaterialTheme.typography.bodySmall, color = BobColors.TextFaint)
    }
}

@Composable
private fun RelayBubble(message: RelayMessage, alignEnd: Boolean) {
    val accent = botColor(message.from)
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(message.from, size = 24.dp)
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = "to",
                tint = BobColors.TextFaint,
                modifier = Modifier.padding(horizontal = 5.dp).size(14.dp),
            )
            BotAvatar(message.to, size = 24.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                message.from,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(" → ", style = MaterialTheme.typography.labelLarge, color = BobColors.TextFaint)
            Text(
                message.to,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = botColor(message.to),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(shape)
                .background(BobColors.BotBubble)
                .border(1.dp, accent.copy(alpha = 0.45f), shape)
                .padding(14.dp),
        ) {
            MarkdownBody(message.text)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            relativeTime(message.at / 1000.0),
            style = MaterialTheme.typography.labelSmall,
            color = BobColors.TextFaint,
        )
    }
}

@Composable
private fun InterjectionBubble(message: RelayMessage) {
    val shape = MaterialTheme.shapes.medium
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(shape)
                .background(BobColors.AmberSoft)
                .border(1.dp, BobColors.Amber.copy(alpha = 0.5f), shape)
                .padding(12.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "You (to both)",
                    style = MaterialTheme.typography.labelSmall,
                    color = BobColors.Amber,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BobColors.Text,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun InterjectBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BobColors.Surface)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BobTextField(
            value = value,
            onValueChange = onValueChange,
            label = if (enabled) "Interject" else "Interject (relay stopped)",
            placeholder = "Say something to both bots…",
            minLines = 1,
            maxLines = 4,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
            Icon(
                Icons.AutoMirrored.Outlined.Send,
                contentDescription = "Send interjection",
                tint = if (enabled && value.isNotBlank()) BobColors.Accent else BobColors.TextFaint,
            )
        }
    }
}
