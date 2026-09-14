package com.bobbot.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bobbot.core.net.int
import com.bobbot.data.repo.ChatItem
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.components.Pill
import com.bobbot.data.repo.botName
import com.bobbot.ui.theme.BobColors
import com.bobbot.ui.theme.botColor
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

@Composable
fun MessageItem(item: ChatItem, profile: String) {
    when (item) {
        is ChatItem.User -> UserBubble(item)
        is ChatItem.Assistant -> AssistantBubble(item, profile)
        is ChatItem.Tool -> ToolCard(item)
        is ChatItem.System -> SystemLine(item)
        is ChatItem.Delegation -> DelegationCard(item, profile)
    }
}

@Composable
fun MarkdownBody(text: String, color: Color = BobColors.Text) {
    Markdown(
        content = text,
        colors = markdownColor(text = color),
        typography = markdownTypography(),
    )
}

@Composable
private fun UserBubble(m: ChatItem.User) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (m.fromBot != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp, end = 4.dp)) {
                Icon(Icons.Outlined.SwapHoriz, null, tint = botColor(m.fromBot), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("from ${botName(m.fromBot)} via BobBot", style = MaterialTheme.typography.labelSmall, color = botColor(m.fromBot))
            }
        }
        Box(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .background(if (m.fromBot != null) BobColors.RelayBubble else BobColors.UserBubble)
                .then(if (m.fromBot != null) Modifier.border(1.dp, botColor(m.fromBot).copy(alpha = 0.5f), RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column {
                if (m.images.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 6.dp)) { m.images.forEach { Pill(it, color = BobColors.Mint) } }
                }
                Text(m.text, color = BobColors.Text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun AssistantBubble(m: ChatItem.Assistant, profile: String) {
    var showReasoning by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        BotAvatar(profile, 28.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            if (m.reasoning.isNotBlank()) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { showReasoning = !showReasoning }.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Psychology, null, tint = BobColors.Violet, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (m.streaming && m.text.isBlank()) "Thinking…" else "Thought process", style = MaterialTheme.typography.labelMedium, color = BobColors.Violet)
                    Icon(if (showReasoning) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = BobColors.Violet, modifier = Modifier.size(16.dp))
                }
                AnimatedVisibility(showReasoning) {
                    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(BobColors.VioletSoft).padding(10.dp)) {
                        Text(m.reasoning, color = BobColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (m.text.isNotBlank()) {
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)).background(BobColors.BotBubble)
                        .border(1.dp, if (m.interim) BobColors.OutlineSoft.copy(alpha = 0.5f) else BobColors.OutlineSoft, RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    MarkdownBody(m.text, color = if (m.interim) BobColors.TextMuted else BobColors.Text)
                }
            } else if (m.streaming && m.reasoning.isBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = botColor(profile))
                    Spacer(Modifier.width(8.dp))
                    Text("…", color = BobColors.TextFaint)
                }
            }
            if (m.error != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = BobColors.Rose, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(m.error, style = MaterialTheme.typography.bodySmall, color = BobColors.Rose)
                }
            } else if (m.status == "interrupted") {
                Text("Stopped", style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
            }
            val usage = m.usage
            if (!m.streaming && usage != null) {
                val inTok = usage.int("input") ?: usage.int("prompt"); val outTok = usage.int("output") ?: usage.int("completion")
                if (inTok != null || outTok != null) Text("${inTok ?: 0} in · ${outTok ?: 0} out", style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
            }
        }
    }
}

@Composable
private fun ToolCard(t: ChatItem.Tool) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 36.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BobColors.Surface).border(1.dp, BobColors.OutlineSoft, RoundedCornerShape(12.dp))
                .clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.done) Icon(Icons.Outlined.CheckCircle, null, tint = BobColors.Mint, modifier = Modifier.size(16.dp))
                else CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = BobColors.Amber)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.Build, null, tint = BobColors.TextMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(t.name, style = MaterialTheme.typography.labelLarge, color = BobColors.Text)
                Spacer(Modifier.width(8.dp))
                Text(t.context, style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                t.durationS?.let { Text("${"%.1f".format(it)}s", style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint) }
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = 8.dp)) {
                    if (!t.args.isNullOrBlank()) { Label("Arguments"); Mono(t.args) }
                    if (!t.summary.isNullOrBlank()) { Label("Summary"); Text(t.summary, color = BobColors.TextMuted, style = MaterialTheme.typography.bodySmall) }
                    if (!t.result.isNullOrBlank()) { Label("Result"); Mono(t.result.take(4000)) }
                    if (t.args.isNullOrBlank() && t.result.isNullOrBlank() && t.summary.isNullOrBlank()) Text("No details available (tool progress detail is off on the server).", color = BobColors.TextFaint, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) { Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }

@Composable
private fun Mono(text: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(BobColors.Bg).padding(8.dp).horizontalScroll(rememberScrollState())) {
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = BobColors.TextMuted)
    }
}

@Composable
private fun SystemLine(s: ChatItem.System) {
    val (icon, color) = when (s.kind) {
        "model_switch" -> Icons.Outlined.SwapHoriz to BobColors.Accent
        "warn" -> Icons.Outlined.ErrorOutline to BobColors.Amber
        "review" -> Icons.Outlined.Psychology to BobColors.Violet
        "async_delegation_complete", "background" -> Icons.Outlined.AccountTree to BobColors.Mint
        else -> Icons.Outlined.Info to BobColors.TextFaint
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(s.text.removePrefix("[System: ").removeSuffix("]"), style = MaterialTheme.typography.labelMedium, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DelegationCard(d: ChatItem.Delegation, profile: String) {
    val running = d.status == "running" || d.status == "thinking" || d.status == "started"
    Row(Modifier.fillMaxWidth().padding(start = 36.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(BobColors.MintSoft.copy(alpha = 0.5f)).border(1.dp, BobColors.Mint.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BotAvatar(profile, 22.dp)
                Icon(Icons.Outlined.SwapHoriz, null, tint = BobColors.Mint, modifier = Modifier.padding(horizontal = 4.dp).size(16.dp))
                BotAvatar("sub-agent", 22.dp, color = BobColors.Mint)
                Spacer(Modifier.width(8.dp))
                Text("${botName(profile)} → sub-agent", style = MaterialTheme.typography.labelLarge, color = BobColors.Mint, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = BobColors.Mint)
                else Pill(d.status, color = if (d.status == "complete") BobColors.Mint else BobColors.Amber)
            }
            Spacer(Modifier.height(6.dp))
            Text(d.goal, color = BobColors.Text, style = MaterialTheme.typography.bodyMedium)
            if (d.model != null || d.toolCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(listOfNotNull(d.model?.substringAfterLast('/'), "${d.toolCount} tool calls".takeIf { d.toolCount > 0 }).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint)
            }
            if (!d.lastText.isNullOrBlank() && running) { Spacer(Modifier.height(4.dp)); Text(d.lastText, style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (!d.summary.isNullOrBlank()) { Spacer(Modifier.height(6.dp)); MarkdownBody(d.summary, color = BobColors.Text) }
        }
    }
}
