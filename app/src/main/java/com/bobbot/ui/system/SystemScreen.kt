package com.bobbot.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.EmptyState
import com.bobbot.ui.components.KeyValueRow
import com.bobbot.ui.components.LoadingRow
import com.bobbot.ui.components.Pill
import com.bobbot.ui.components.SectionHeader
import com.bobbot.ui.components.StatusDot
import com.bobbot.ui.theme.BobColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(
    onBack: () -> Unit,
    vm: SystemViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmAction by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = BobColors.Bg,
        topBar = {
            TopAppBar(
                title = { Text("System") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Outlined.Autorenew, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BobColors.Bg),
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading && state.status == null) LoadingRow("Reading system state…")

            state.error?.let { ErrorCard(it) }

            // ---------- Status ----------
            SectionHeader("Status")
            BobCard {
                val st = state.status
                if (st == null) {
                    Text("No status yet", color = BobColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
                } else {
                    KeyValueRow("Hermes version", st.version)
                    KeyValueRow("Overall", st.overall)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text("Gateway", color = BobColors.TextMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        StatusDot(st.gatewayRunning)
                        Spacer(Modifier.width(8.dp))
                        Text(st.gatewayState, color = BobColors.Text, style = MaterialTheme.typography.bodyMedium)
                    }
                    KeyValueRow("Active sessions", "${st.activeSessions}")
                    if (st.platforms.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Platforms", color = BobColors.TextFaint, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            st.platforms.forEach { (name, pstate) ->
                                val ok = pstate.equals("running", true) || pstate.equals("connected", true) || pstate.equals("ok", true)
                                Pill("$name · $pstate", color = if (ok) BobColors.Mint else BobColors.TextFaint)
                            }
                        }
                    }
                }
            }

            // ---------- Host ----------
            SectionHeader("Host")
            BobCard {
                val host = state.host
                when {
                    state.hostError != null -> Text(state.hostError ?: "", color = BobColors.Rose, style = MaterialTheme.typography.bodyMedium)
                    host == null -> Text("No host stats", color = BobColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        if (host.hostname.isNotBlank()) KeyValueRow("Hostname", host.hostname)
                        if (host.os.isNotBlank()) KeyValueRow("OS", host.os)
                        if (host.uptime.isNotBlank()) KeyValueRow("Uptime", host.uptime)
                        Spacer(Modifier.height(8.dp))
                        MeterRow("CPU", host.cpu)
                        MeterRow("Memory", host.memory)
                        MeterRow("Disk", host.disk)
                    }
                }
            }

            // ---------- Usage ----------
            SectionHeader("Usage · last 30 days")
            BobCard {
                when {
                    state.usageError != null -> Text(state.usageError ?: "", color = BobColors.Rose, style = MaterialTheme.typography.bodyMedium)
                    state.usage.isEmpty() -> Text("No usage reported", color = BobColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
                    else -> state.usage.forEach { (k, v) -> KeyValueRow(k, v) }
                }
            }

            // ---------- Gateway controls ----------
            SectionHeader("Gateway")
            BobCard {
                Text(
                    "Restarting drops every live session on the server.",
                    color = BobColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { confirmAction = "restart" },
                        enabled = state.gatewayBusy == null,
                    ) { Text("Restart") }
                    OutlinedButton(
                        onClick = { confirmAction = "start" },
                        enabled = state.gatewayBusy == null,
                    ) { Text("Start") }
                    OutlinedButton(
                        onClick = { confirmAction = "stop" },
                        enabled = state.gatewayBusy == null,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BobColors.Rose),
                    ) { Text("Stop") }
                    if (state.gatewayBusy != null) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BobColors.Accent)
                    }
                }
                state.gatewayMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = if (state.gatewayFailed) BobColors.Rose else BobColors.Mint,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ---------- Logs ----------
            SectionHeader("Logs")
            BobCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PickerButton(
                        label = state.logFile,
                        options = SystemViewModel.LOG_FILES.map { it to it },
                        onPick = vm::setLogFile,
                    )
                    PickerButton(
                        label = state.logLevel ?: "All levels",
                        options = SystemViewModel.LOG_LEVELS.map { it to (it ?: "All levels") },
                        onPick = vm::setLogLevel,
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.logsLoading) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BobColors.Accent)
                    } else {
                        IconButton(onClick = vm::loadLogs) {
                            Icon(Icons.Outlined.Autorenew, contentDescription = "Reload logs", tint = BobColors.TextMuted)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                when {
                    state.logsError != null -> Text(state.logsError ?: "", color = BobColors.Rose, style = MaterialTheme.typography.bodyMedium)
                    state.logLines.isEmpty() && !state.logsLoading ->
                        EmptyState("No log lines", "Nothing matched this file and level.")
                    else -> LogBox(state.logLines)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            containerColor = BobColors.SurfaceRaised,
            title = { Text("${action.replaceFirstChar { c -> c.uppercaseChar() }} gateway?") },
            text = {
                Text(
                    when (action) {
                        "restart" -> "Every active session on the server will be dropped."
                        "stop" -> "Bots will stop responding until the gateway is started again."
                        else -> "The gateway will be brought up."
                    },
                    color = BobColors.TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.gateway(action); confirmAction = null }) {
                    Text(action.replaceFirstChar { c -> c.uppercaseChar() }, color = if (action == "stop") BobColors.Rose else BobColors.Accent)
                }
            },
            dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    BobCard(container = BobColors.RoseSoft, border = BobColors.Rose.copy(alpha = 0.4f)) {
        Text(message, color = BobColors.Rose, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MeterRow(label: String, percent: Double?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = BobColors.TextMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                percent?.let { "${it.toInt()}%" } ?: "—",
                color = BobColors.Text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(6.dp))
        val fraction = ((percent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
        val bar = when {
            fraction >= 0.9f -> BobColors.Rose
            fraction >= 0.7f -> BobColors.Amber
            else -> BobColors.Accent
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = bar,
            trackColor = BobColors.SurfaceHigh,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun <T> PickerButton(label: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        open = false
                        onPick(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun LogBox(lines: List<String>) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BobColors.Bg)
            .border(1.dp, BobColors.OutlineSoft, RoundedCornerShape(12.dp)),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            lines.forEach { line ->
                Text(
                    line,
                    color = severityColor(line),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun severityColor(line: String): Color = when {
    line.contains("ERROR") || line.contains("CRITICAL") || line.contains("Traceback") -> BobColors.Rose
    line.contains("WARNING") || line.contains("WARN") -> BobColors.Amber
    else -> BobColors.TextMuted
}
