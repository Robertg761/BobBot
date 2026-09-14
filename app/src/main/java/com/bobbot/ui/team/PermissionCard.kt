package com.bobbot.ui.team

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bobbot.data.repo.TeamRequest
import com.bobbot.data.repo.botName
import com.bobbot.ui.board.relativeTime
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.components.Pill
import com.bobbot.ui.theme.BobColors
import com.bobbot.ui.theme.botColor

/**
 * One permission request, readable at a glance: who, which tool, the command or path, what the
 * authority said, and the three choices. Used in a bot's chat and on the Team screen.
 *
 * `onDecide(choice, scope)`: choice is "approved" or "denied"; scope "exact" allows this action
 * once, "tool" allows the tool for the rest of that conversation.
 */
@Composable
fun PermissionCard(
    request: TeamRequest,
    authority: String,
    busy: Boolean,
    onDecide: (choice: String, scope: String) -> Unit,
    showHistory: Boolean = false,
    /** The stored id of the chat this card is shown in, to warn when the request came from another one. */
    currentSession: String? = null,
) {
    val who = botName(request.profile)
    val boss = botName(authority)
    val accent = when {
        request.needsYou -> BobColors.Rose
        request.waitingForAuthority -> BobColors.Amber
        request.status == "denied" -> BobColors.Rose
        request.status in setOf("approved", "consumed") -> BobColors.Mint
        else -> BobColors.TextMuted
    }
    var deciding by remember(request.id) { mutableStateOf(request.needsYou) }

    BobCard(border = accent.copy(alpha = if (request.open) 0.5f else 0.2f), padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(request.profile, 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("$who wants to use ${request.tool}", style = MaterialTheme.typography.titleSmall, color = BobColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        request.needsYou -> "$boss asked you to decide"
                        request.waitingForAuthority -> "$boss is reviewing"
                        request.expired && request.status == "approved" -> "Allowed, but not used in time"
                        request.expired -> "Expired without a decision"
                        request.status == "consumed" -> "Allowed once and used"
                        request.status == "approved" && request.scope == "tool" -> "Allowed for that conversation"
                        request.status == "approved" -> "Allowed once"
                        request.status == "denied" -> "Denied"
                        request.status == "expired" -> "No decision in time"
                        else -> request.status.replace('_', ' ')
                    } + " · " + relativeTime(request.createdMillis / 1000.0),
                    style = MaterialTheme.typography.labelSmall, color = accent,
                )
            }
            if (request.task.isNotBlank()) Pill("board task", color = BobColors.TextMuted)
        }

        request.headline?.let { head ->
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(BobColors.Bg).padding(10.dp).horizontalScroll(rememberScrollState())) {
                Text(head.take(600), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = BobColors.Text, maxLines = 8)
            }
        }
        val rest = request.details.entries.take(4)
        if (rest.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            rest.forEach { (k, v) ->
                Text("$k: ${v.take(120)}", style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (request.reason.isNotBlank() && (request.needsYou || showHistory || !request.open)) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${if (request.reviewer == "you") "You" else boss}: ${request.reason}",
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = BobColors.TextMuted,
                maxLines = if (showHistory) 3 else 6, overflow = TextOverflow.Ellipsis,
            )
        }

        if (request.open) {
            Spacer(Modifier.height(10.dp))
            if (!deciding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("You can decide before $boss does.", style = MaterialTheme.typography.bodySmall, color = BobColors.TextFaint, modifier = Modifier.weight(1f))
                    TextButton(onClick = { deciding = true }, enabled = !busy) { Text("Decide now", color = BobColors.Accent) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { onDecide("approved", "exact") }, enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = botColor(request.profile), contentColor = BobColors.Bg),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) { Text("Allow") }
                    TextButton(onClick = { onDecide("approved", "tool") }, enabled = !busy) { Text("Allow ${request.tool} here", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onDecide("denied", "exact") }, enabled = !busy) { Text("Deny", color = BobColors.Rose) }
                }
                val elsewhere = currentSession != null && request.task.isBlank() && request.session.isNotBlank() && request.session != currentSession
                Text(
                    (if (elsewhere) "Asked from one of $who's task chats: after you decide, tell it there to try again. " else "") +
                        "Allow: this once. Allow ${request.tool} here: any use of that tool in this conversation for 8 hours. Hermes' own approval rules still apply.",
                    style = MaterialTheme.typography.labelSmall, color = if (elsewhere) BobColors.Amber else BobColors.TextFaint,
                )
            }
        }
    }
}

/** A one-line strip for a chat header: how many decisions are waiting, and on whom. */
@Composable
fun PermissionSummaryLine(needsYou: Int, waiting: Int, authority: String, color: Color = BobColors.TextMuted) {
    val parts = buildList {
        if (needsYou > 0) add("$needsYou for you")
        if (waiting > 0) add("$waiting for ${botName(authority)}")
    }
    if (parts.isNotEmpty()) Text("Permissions waiting: " + parts.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = color)
}
