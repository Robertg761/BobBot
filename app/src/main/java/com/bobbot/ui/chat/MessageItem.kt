package com.bobbot.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bobbot.data.repo.ChatItem
import com.bobbot.data.repo.botName
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.theme.BobColors
import com.bobbot.ui.theme.botColor
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

private val BubbleRadius = 20.dp
private val TightRadius = 6.dp
private val BubbleMaxWidth = 300.dp

/**
 * One transcript entry. `groupedAbove` / `groupedBelow` say whether the neighbour is from the
 * same sender, which tightens the bubble corners and spacing the way a messages app does.
 */
@Composable
fun MessageItem(item: ChatItem, profile: String, groupedAbove: Boolean = false, groupedBelow: Boolean = false) {
    when (item) {
        is ChatItem.User -> UserBubble(item, groupedAbove, groupedBelow)
        is ChatItem.Assistant -> AssistantBubble(item, profile, groupedAbove, groupedBelow)
        is ChatItem.Tool -> ActivityLine(item)
        is ChatItem.System -> SystemLine(item)
        is ChatItem.Delegation -> DelegationCard(item, profile)
    }
}

@Composable
fun MarkdownBody(text: String, color: Color = BobColors.Text) {
    Markdown(
        content = text,
        colors = markdownColor(text = color, codeBackground = BobColors.Bg.copy(alpha = 0.6f), inlineCodeBackground = BobColors.Bg.copy(alpha = 0.6f)),
        typography = markdownTypography(),
    )
}

/** Corners: the side facing the sender gets tight where bubbles are stacked. */
fun bubbleShape(mine: Boolean, groupedAbove: Boolean, groupedBelow: Boolean): RoundedCornerShape {
    val top = if (groupedAbove) TightRadius else BubbleRadius
    val bottom = if (groupedBelow) TightRadius else BubbleRadius
    return if (mine) RoundedCornerShape(topStart = BubbleRadius, topEnd = top, bottomEnd = bottom, bottomStart = BubbleRadius)
    else RoundedCornerShape(topStart = top, topEnd = BubbleRadius, bottomEnd = BubbleRadius, bottomStart = bottom)
}

@Composable
private fun UserBubble(m: ChatItem.User, groupedAbove: Boolean, groupedBelow: Boolean) {
    Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End) {
        Box(
            Modifier.widthIn(max = BubbleMaxWidth).clip(bubbleShape(mine = true, groupedAbove = groupedAbove, groupedBelow = groupedBelow))
                .background(BobColors.UserBubble)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Column {
                if (m.images.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                        Icon(Icons.Outlined.Image, null, tint = BobColors.UserBubbleText.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (m.images.size == 1) "1 image" else "${m.images.size} images", style = MaterialTheme.typography.labelSmall, color = BobColors.UserBubbleText.copy(alpha = 0.8f))
                    }
                }
                if (m.text.isNotBlank()) Text(m.text, color = BobColors.UserBubbleText, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun AssistantBubble(m: ChatItem.Assistant, profile: String, groupedAbove: Boolean, groupedBelow: Boolean) {
    var showReasoning by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(end = 40.dp), horizontalAlignment = Alignment.Start) {
        if (m.reasoning.isNotBlank()) {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { showReasoning = !showReasoning }.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Psychology, null, tint = BobColors.Violet, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (m.streaming && m.text.isBlank()) "Thinking…" else "Thought process", style = MaterialTheme.typography.labelSmall, color = BobColors.Violet)
                Icon(if (showReasoning) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = BobColors.Violet, modifier = Modifier.size(14.dp))
            }
            AnimatedVisibility(showReasoning) {
                Box(Modifier.widthIn(max = BubbleMaxWidth).padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(BobColors.VioletSoft).padding(10.dp)) {
                    Text(m.reasoning, color = BobColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (m.text.isNotBlank()) {
            Box(
                Modifier.widthIn(max = BubbleMaxWidth)
                    .clip(bubbleShape(mine = false, groupedAbove = groupedAbove, groupedBelow = groupedBelow))
                    .background(BobColors.BotBubble)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                MarkdownBody(m.text, color = if (m.interim) BobColors.TextMuted else BobColors.Text)
            }
        }
        if (m.error != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, start = 4.dp)) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = BobColors.Rose, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(m.error, style = MaterialTheme.typography.bodySmall, color = BobColors.Rose)
            }
        } else if (m.status == "interrupted") {
            Text("Stopped", style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint, modifier = Modifier.padding(top = 3.dp, start = 6.dp))
        }
    }
}

/** The three bouncing dots a messages app shows while the other side is typing. */
@Composable
fun TypingBubble(color: Color = BobColors.TextMuted, label: String? = null) {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "dots",
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Row(
            Modifier.clip(RoundedCornerShape(BubbleRadius)).background(BobColors.BotBubble).padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { i ->
                val d = ((phase - i + 3f) % 3f)
                val a = if (d < 1f) 0.35f + 0.65f * (1f - d) else 0.35f
                Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(color))
            }
        }
        if (!label.isNullOrBlank()) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 6.dp))
        }
    }
}

/** A tool call, shown as a quiet activity line rather than a card; tap for details. */
@Composable
private fun ActivityLine(t: ChatItem.Tool) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(end = 24.dp).clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (t.done) Icon(Icons.Outlined.CheckCircle, null, tint = BobColors.TextFaint, modifier = Modifier.size(13.dp))
            else CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = BobColors.Amber)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Outlined.Build, null, tint = BobColors.TextFaint, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                listOf(t.name, t.context.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = BobColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            t.durationS?.let { Spacer(Modifier.width(6.dp)); Text("${"%.1f".format(it)}s", style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint.copy(alpha = 0.7f)) }
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 6.dp)) {
                if (!t.args.isNullOrBlank()) { Label("Arguments"); Mono(t.args) }
                if (!t.summary.isNullOrBlank()) { Label("Summary"); Text(t.summary, color = BobColors.TextMuted, style = MaterialTheme.typography.bodySmall) }
                if (!t.result.isNullOrBlank()) { Label("Result"); Mono(t.result.take(4000)) }
                if (t.args.isNullOrBlank() && t.result.isNullOrBlank() && t.summary.isNullOrBlank()) Text("No details available (tool progress detail is off on the server).", color = BobColors.TextFaint, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Label(text: String) { Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }

@Composable
private fun Mono(text: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(BobColors.Surface).padding(8.dp).horizontalScroll(rememberScrollState())) {
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = BobColors.TextMuted)
    }
}

@Composable
private fun SystemLine(s: ChatItem.System) {
    val (icon, color) = when (s.kind) {
        "model_switch" -> Icons.Outlined.SwapHoriz to BobColors.TextMuted
        "warn" -> Icons.Outlined.ErrorOutline to BobColors.Amber
        "review" -> Icons.Outlined.Psychology to BobColors.Violet
        "async_delegation_complete", "background" -> Icons.Outlined.AccountTree to BobColors.Mint
        else -> Icons.Outlined.Info to BobColors.TextFaint
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(s.text.removePrefix("[System: ").removeSuffix("]"), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DelegationCard(d: ChatItem.Delegation, profile: String) {
    val running = d.status == "running" || d.status == "thinking" || d.status == "started"
    Row(Modifier.fillMaxWidth().padding(end = 32.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(BobColors.MintSoft.copy(alpha = 0.45f)).padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BotAvatar(profile, 22.dp)
                Icon(Icons.Outlined.SwapHoriz, null, tint = BobColors.Mint, modifier = Modifier.padding(horizontal = 4.dp).size(16.dp))
                BotAvatar("sub-agent", 22.dp, color = BobColors.Mint)
                Spacer(Modifier.width(8.dp))
                Text("${botName(profile)} → sub-agent", style = MaterialTheme.typography.labelLarge, color = BobColors.Mint, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = BobColors.Mint)
                else Text(d.status, style = MaterialTheme.typography.labelSmall, color = if (d.status == "complete") BobColors.Mint else BobColors.Amber)
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

/** Spacing between two transcript entries: tight inside a sender's run, roomy between senders. */
fun gapBetween(groupedAbove: Boolean): Dp = if (groupedAbove) 3.dp else 12.dp
