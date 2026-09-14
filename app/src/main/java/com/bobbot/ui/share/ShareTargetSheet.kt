package com.bobbot.ui.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bobbot.data.model.Bot
import com.bobbot.data.repo.botName
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.components.LoadingRow
import com.bobbot.ui.theme.BobColors

/** Which bot should get what another app just shared. Skipped when the user only has one bot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetSheet(bots: List<Bot>, payload: SharePayload, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = BobColors.SurfaceRaised) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Send to", style = MaterialTheme.typography.titleLarge, color = BobColors.Text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(payload.summary, style = MaterialTheme.typography.bodyMedium, color = BobColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            if (bots.isEmpty()) {
                LoadingRow("Finding your bots…", Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(bots, key = { it.name }) { bot -> BotRow(bot, onPick) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun BotRow(bot: Bot, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPick(bot.name) }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotAvatar(bot.name, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(botName(bot.name), style = MaterialTheme.typography.titleMedium, color = BobColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (bot.description.isNotBlank()) {
                Text(bot.description, style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
