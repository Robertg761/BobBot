package com.bobbot.ui.setup

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobbot.core.auth.AuthState
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.theme.BobColors

/** [pairUrl] arrives from a bobbot://pair QR code: the address is filled in and checked for you. */
@Composable
fun SetupScreen(onDone: () -> Unit, pairUrl: String? = null, vm: SetupViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    LaunchedEffect(pairUrl) { if (!pairUrl.isNullOrBlank()) vm.pairWith(pairUrl) }

    Box(Modifier.fillMaxSize().background(BobColors.Bg).systemBarsPadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            StepDots(ui.step)
            Spacer(Modifier.height(28.dp))
            AnimatedContent(targetState = ui.step, label = "setup") { step ->
                when (step) {
                    SetupStep.Welcome -> Welcome(onNext = { vm.go(SetupStep.Server) })
                    SetupStep.Server -> ServerStep(ui, vm)
                    SetupStep.SignIn -> SignInStep(ui, vm, openUrl = { url ->
                        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(ctx, Uri.parse(url))
                    })
                    SetupStep.Notifications -> NotificationsStep(ui, vm, onDone = onDone)
                    SetupStep.Ready -> Welcome(onNext = onDone)
                }
            }
        }
    }
}

@Composable
private fun StepDots(step: SetupStep) {
    val steps = listOf(SetupStep.Welcome, SetupStep.Server, SetupStep.SignIn, SetupStep.Notifications)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEach { s ->
            val active = s.ordinal <= step.ordinal
            Box(
                Modifier.height(4.dp).width(if (s == step) 28.dp else 16.dp).clip(CircleShape)
                    .background(if (active) BobColors.Accent else BobColors.OutlineSoft),
            )
        }
    }
}

@Composable
private fun Title(text: String, sub: String? = null) {
    Text(text, style = MaterialTheme.typography.displaySmall, color = BobColors.Text)
    if (sub != null) {
        Spacer(Modifier.height(8.dp))
        Text(sub, style = MaterialTheme.typography.bodyLarge, color = BobColors.TextMuted)
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    Column {
        Box(Modifier.size(64.dp).clip(CircleShape).background(BobColors.AccentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.SmartToy, null, tint = BobColors.Accent, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(20.dp))
        Title("BobBot", "Your Hermes bots, on your phone.")
        Spacer(Modifier.height(24.dp))
        Feature(Icons.Outlined.SmartToy, "Every bot, one conversation", "Your bots sit in an inbox like contacts. Open one and keep talking; the same chat continues on the Hermes desktop.")
        Feature(Icons.Outlined.Hub, "Bots that work together", "Your main bot can create others, hand them work, and message them. Put a few in a group when a job needs it.")
        Feature(Icons.Outlined.NotificationsActive, "They can reach you", "Automations and bots push notifications straight to this phone.")
        Spacer(Modifier.height(32.dp))
        Primary("Get started", onNext)
    }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(Modifier.padding(vertical = 10.dp)) {
        Icon(icon, null, tint = BobColors.Mint, modifier = Modifier.size(22.dp).padding(top = 2.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = BobColors.Text)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = BobColors.TextMuted)
        }
    }
}

@Composable
private fun Primary(text: String, onClick: () -> Unit, enabled: Boolean = true, busy: Boolean = false) {
    Button(
        onClick = onClick, enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = BobColors.Accent, contentColor = BobColors.Bg),
        shape = MaterialTheme.shapes.medium,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BobColors.Bg)
        else Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ServerStep(ui: SetupUi, vm: SetupViewModel) {
    Column {
        Title("Connect to Hermes", "Enter the address of the dashboard running on your computer. It listens on port 9119.")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = ui.url, onValueChange = vm::setUrl,
            label = { Text("Server address") },
            placeholder = { Text("192.168.1.20:9119") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
        )
        Spacer(Modifier.height(8.dp))
        Text("Tip: use your Tailscale or LAN IP. If you started the dashboard with a hostname, use that exact hostname.", style = MaterialTheme.typography.bodySmall, color = BobColors.TextFaint)
        ErrorLine(ui.error)
        if (ui.serverOk == true) {
            Spacer(Modifier.height(12.dp))
            BobCard(container = BobColors.MintSoft, border = BobColors.Mint.copy(alpha = 0.4f)) {
                Text("Found Hermes ${ui.serverVersion}", color = BobColors.Mint, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(24.dp))
        Primary("Check connection", vm::checkServer, busy = ui.checking)
        TextButton(onClick = { vm.go(SetupStep.Welcome) }, modifier = Modifier.padding(top = 8.dp)) { Text("Back", color = BobColors.TextMuted) }
    }
}

@Composable
private fun SignInStep(ui: SetupUi, vm: SetupViewModel, openUrl: (String) -> Unit) {
    Column {
        if (ui.authRequired) {
            Title("Sign in", "Hermes will open your browser so you can sign in with your Nous account. You'll be sent straight back here.")
            Spacer(Modifier.height(24.dp))
            when (val a = ui.auth) {
                is AuthState.WaitingForBrowser -> {
                    BobCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BobColors.Accent)
                            Spacer(Modifier.width(12.dp))
                            Text("Waiting for you to finish in the browser…", color = BobColors.TextMuted)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { openUrl(a.authorizeUrl) }, modifier = Modifier.fillMaxWidth()) { Text("Reopen browser") }
                    TextButton(onClick = vm::cancelSignIn) { Text("Cancel", color = BobColors.TextMuted) }
                }
                AuthState.Exchanging -> BobCard { Text("Finishing sign-in…", color = BobColors.TextMuted) }
                else -> {
                    ErrorLine(ui.error)
                    Spacer(Modifier.height(8.dp))
                    Primary("Sign in with Nous", { vm.beginSignIn(openUrl) })
                }
            }
        } else {
            Title("Session token", "This dashboard is bound to localhost, so it uses a static session token instead of a login. Paste the token from HERMES_DASHBOARD_SESSION_TOKEN on your computer.")
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(value = ui.sessionToken, onValueChange = vm::setSessionToken, label = { Text("Session token") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            ErrorLine(ui.error)
            Spacer(Modifier.height(24.dp))
            Primary("Continue", vm::useSessionToken, enabled = ui.sessionToken.isNotBlank(), busy = ui.checking)
        }
        TextButton(onClick = { vm.go(SetupStep.Server) }, modifier = Modifier.padding(top = 8.dp)) { Text("Change server", color = BobColors.TextMuted) }
    }
}

@Composable
private fun NotificationsStep(ui: SetupUi, vm: SetupViewModel, onDone: () -> Unit) {
    Column {
        Title(if (ui.displayName.isNotBlank()) "Hi ${ui.displayName.substringBefore(' ')}" else "You're in", "Found ${ui.bots.size} bot${if (ui.bots.size == 1) "" else "s"}. One last thing: let bots reach you.")
        Spacer(Modifier.height(16.dp))
        if (ui.bots.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                ui.bots.take(6).forEach { BotAvatar(it.name, 36.dp) }
            }
            Spacer(Modifier.height(20.dp))
        }
        BobCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NotificationsActive, null, tint = BobColors.Accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Bot notifications", style = MaterialTheme.typography.titleMedium, color = BobColors.Text)
                    Text("Replies, automations and bot-to-bot messages", style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted)
                }
                Switch(checked = ui.notificationsOn, onCheckedChange = vm::setNotificationsOn)
            }
            if (ui.notificationsOn) {
                Spacer(Modifier.height(16.dp))
                Text("Push channel (ntfy)", style = MaterialTheme.typography.labelMedium, color = BobColors.TextFaint)
                Spacer(Modifier.height(6.dp))
                Text("BobBot gives Hermes a private ntfy topic. Anything a bot sends there shows up on this phone, even when the app is closed.", style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = ui.ntfyTopic, onValueChange = vm::setNtfyTopic, label = { Text("Topic") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                    trailingIcon = { TextButton(onClick = vm::generateTopic) { Text("Generate") } })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = ui.ntfyServer, onValueChange = vm::setNtfyServer, label = { Text("ntfy server") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                Spacer(Modifier.height(4.dp))
                Text("Treat the topic like a password. Anyone who knows it can read the messages.", style = MaterialTheme.typography.bodySmall, color = BobColors.TextFaint)
            }
        }
        ErrorLine(ui.ntfyResult?.takeIf { it.contains("could not", ignoreCase = true) })
        ui.ntfyResult?.takeIf { !it.contains("could not", ignoreCase = true) }?.let {
            Spacer(Modifier.height(8.dp)); Text(it, color = BobColors.Mint, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Primary("Finish", { vm.saveNotifications(onDone) }, busy = ui.ntfyBusy)
        TextButton(onClick = { vm.setNotificationsOn(false); vm.saveNotifications(onDone) }, modifier = Modifier.padding(top = 8.dp)) { Text("Skip for now", color = BobColors.TextMuted) }
    }
}

@Composable
private fun ErrorLine(msg: String?) {
    if (msg.isNullOrBlank()) return
    Spacer(Modifier.height(12.dp))
    BobCard(container = BobColors.RoseSoft, border = BobColors.Rose.copy(alpha = 0.4f)) {
        Text(msg, color = BobColors.Rose, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BobColors.Accent,
    unfocusedBorderColor = BobColors.Outline,
    focusedLabelColor = BobColors.Accent,
    unfocusedLabelColor = BobColors.TextMuted,
    cursorColor = BobColors.Accent,
    focusedTextColor = BobColors.Text,
    unfocusedTextColor = BobColors.Text,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
)
