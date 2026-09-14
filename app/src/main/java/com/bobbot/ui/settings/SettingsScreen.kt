package com.bobbot.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobbot.service.LinkService
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.KeyValueRow
import com.bobbot.ui.components.Pill
import com.bobbot.ui.components.SectionHeader
import com.bobbot.ui.components.StatusDot
import com.bobbot.ui.theme.BobColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)?,
    onSignedOut: () -> Unit,
    onOpenSystem: () -> Unit,
    onOpenModels: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    var editUrl by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var linkRunning by remember { mutableStateOf(LinkService.isRunning) }

    LaunchedEffect(Unit) {
        while (true) {
            linkRunning = LinkService.isRunning
            delay(1_500)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Start either way: the link still runs, it just posts nothing until the user relents.
        LinkService.start(ctx)
        linkRunning = LinkService.isRunning
    }

    Scaffold(
        containerColor = BobColors.Bg,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
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
            if (state.error != null) {
                BobCard(container = BobColors.RoseSoft, border = BobColors.Rose.copy(alpha = 0.4f)) {
                    Text(state.error ?: "", color = BobColors.Rose, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // ---------- Connection ----------
            SectionHeader("Connection")
            BobCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Server", color = BobColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            state.baseUrl.ifBlank { "Not configured" },
                            color = BobColors.Text,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { editUrl = true }) { Text("Change") }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = BobColors.OutlineSoft)
                KeyValueRow("Signed in as", state.displayName.ifBlank { state.email.ifBlank { "unknown" } })
                if (state.email.isNotBlank() && state.displayName.isNotBlank()) KeyValueRow("Account", state.email)
                KeyValueRow("Server version", state.serverVersion.ifBlank { "—" })
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text("Gateway", color = BobColors.TextMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    StatusDot(state.gatewayOk)
                    Spacer(Modifier.width(8.dp))
                    Text(state.gatewayState.ifBlank { "unknown" }, color = BobColors.Text, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = vm::testConnection, enabled = !state.testing) {
                        if (state.testing) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = BobColors.Accent)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Test connection")
                    }
                    TextButton(onClick = { confirmSignOut = true }) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, null, tint = BobColors.Rose, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sign out", color = BobColors.Rose)
                    }
                }
                state.testResult?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        color = if (state.testFailed) BobColors.Rose else BobColors.Mint,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ---------- Notifications ----------
            SectionHeader("Notifications")
            BobCard {
                ToggleRow(
                    title = "Bot notifications",
                    subtitle = "Let bots reach you when BobBot is closed",
                    checked = state.notificationsEnabled,
                    onCheckedChange = { on ->
                        vm.setNotificationsEnabled(on)
                        if (on) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                LinkService.start(ctx)
                            }
                        } else {
                            LinkService.stop(ctx)
                        }
                        linkRunning = LinkService.isRunning
                    },
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = BobColors.OutlineSoft)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(linkRunning)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Background link", color = BobColors.Text, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (linkRunning) "Running · streaming bot messages" else "Stopped",
                            color = BobColors.TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = {
                        if (linkRunning) LinkService.stop(ctx) else LinkService.start(ctx)
                        linkRunning = !linkRunning
                    }) { Text(if (linkRunning) "Stop" else "Start") }
                }
            }

            BobCard {
                ToggleRow(
                    title = "Board activity",
                    subtitle = "When one bot assigns, comments or completes for another",
                    checked = state.watchBoard,
                    onCheckedChange = { vm.setWatch(board = it) },
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = BobColors.OutlineSoft)
                ToggleRow(
                    title = "Automation results",
                    subtitle = "Output from scheduled jobs that don't deliver via ntfy",
                    checked = state.watchCron,
                    onCheckedChange = { vm.setWatch(cron = it) },
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = BobColors.OutlineSoft)
                ToggleRow(
                    title = "Relay conversations",
                    subtitle = "Bot-to-bot chats you started from BobBot",
                    checked = state.watchRelay,
                    onCheckedChange = { vm.setWatch(relay = it) },
                )
            }

            // ---------- ntfy ----------
            BobCard {
                Text("ntfy push", color = BobColors.Text, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Bots deliver to this topic through Hermes' ntfy channel, and BobBot listens to it directly.",
                    color = BobColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.ntfyServer,
                    onValueChange = vm::onNtfyServerChange,
                    label = { Text("Server") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.ntfyTopic,
                        onValueChange = vm::onNtfyTopicChange,
                        label = { Text("Topic") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = vm::generateTopic) { Text("Generate") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.ntfyToken,
                    onValueChange = vm::onNtfyTokenChange,
                    label = { Text("Token (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::saveNtfy, enabled = !state.ntfyBusy) { Text("Save") }
                    OutlinedButton(onClick = vm::sendTestPush, enabled = !state.ntfyBusy) { Text("Send test") }
                    if (state.ntfyBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BobColors.Accent)
                }
                state.ntfyMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = BobColors.Mint, style = MaterialTheme.typography.bodySmall)
                }
                state.ntfyError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = BobColors.Rose, style = MaterialTheme.typography.bodySmall)
                }
            }

            // ---------- Shortcuts ----------
            SectionHeader("Shortcuts")
            BobCard(padding = PaddingValues(vertical = 4.dp)) {
                ShortcutRow("Models", "Providers and model assignments", Icons.Outlined.Memory, onOpenModels)
                HorizontalDivider(color = BobColors.OutlineSoft)
                ShortcutRow("System", "Host stats, usage, gateway and logs", Icons.Outlined.Tune, onOpenSystem)
            }

            // ---------- About ----------
            SectionHeader("About")
            BobCard {
                KeyValueRow("BobBot", state.appVersion.ifBlank { "—" })
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("Built for Hermes Agent", color = BobColors.Accent)
                    if (state.serverVersion.isNotBlank()) Pill("Hermes ${state.serverVersion}", color = BobColors.Mint)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (editUrl) {
        var draft by remember { mutableStateOf(state.baseUrl) }
        AlertDialog(
            onDismissRequest = { editUrl = false },
            containerColor = BobColors.SurfaceRaised,
            title = { Text("Server address") },
            text = {
                Column {
                    Text(
                        "Host or full URL. Port 9119 is assumed when you leave it out.",
                        color = BobColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        placeholder = { Text("http://192.168.1.20:9119") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveBaseUrl(draft); editUrl = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editUrl = false }) { Text("Cancel") } },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            containerColor = BobColors.SurfaceRaised,
            title = { Text("Sign out?") },
            text = { Text("BobBot will forget your tokens and return to setup.", color = BobColors.TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    LinkService.stop(ctx)
                    vm.signOut(onSignedOut)
                }) { Text("Sign out", color = BobColors.Rose) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = BobColors.Text, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = BobColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BobColors.Bg,
                checkedTrackColor = BobColors.Accent,
                uncheckedThumbColor = BobColors.TextFaint,
                uncheckedTrackColor = BobColors.SurfaceHigh,
            ),
        )
    }
}

@Composable
private fun ShortcutRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .height(60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = BobColors.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = BobColors.Text, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = BobColors.TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = BobColors.TextFaint)
    }
}
