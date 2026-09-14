package com.bobbot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bobbot.data.repo.CompletionNotice
import com.bobbot.data.repo.botName
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.theme.BobColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The cue a notification would have given. While BobBot is in the foreground Hermes' pushes are
 * suppressed, so a bot the user is not currently reading would otherwise finish in silence.
 * Tap to open that conversation; swipe it aside or press the cross to let it go.
 */
@Composable
fun ReplyBanner(notice: CompletionNotice?, onOpen: (CompletionNotice) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // Keep the last notice through the exit animation, otherwise the banner empties as it slides away.
    var shown by remember { mutableStateOf<CompletionNotice?>(null) }
    LaunchedEffect(notice) { if (notice != null) shown = notice }
    val content = notice ?: shown

    AnimatedVisibility(
        visible = notice != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        if (content != null) BannerCard(content, onOpen = { onOpen(content) }, onDismiss = onDismiss)
    }
}

@Composable
private fun BannerCard(notice: CompletionNotice, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val slide = remember(notice) { Animatable(0f) }
    val failed = notice.status == "error"
    val accent = if (failed) BobColors.Rose else BobColors.Accent

    Row(
        Modifier
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .offset { IntOffset(slide.value.roundToInt(), 0) }
            .draggable(
                state = rememberDraggableState { delta -> scope.launch { slide.snapTo(slide.value + delta) } },
                orientation = Orientation.Horizontal,
                onDragStopped = { if (abs(slide.value) > 120f) onDismiss() else slide.animateTo(0f) },
            )
            .shadow(12.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(BobColors.SurfaceHigh)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotAvatar(notice.profile, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    botName(notice.profile), style = MaterialTheme.typography.titleSmall, color = BobColors.Text,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (failed) {
                    Spacer(Modifier.width(6.dp))
                    Text("couldn't finish", style = MaterialTheme.typography.labelMedium, color = BobColors.Rose, maxLines = 1)
                }
            }
            Text(
                notice.preview.replace('\n', ' ').trim().ifBlank { if (failed) "Something went wrong." else "Replied." },
                style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Outlined.Close, "Dismiss", tint = BobColors.TextFaint, modifier = Modifier.size(18.dp))
        }
    }
}
