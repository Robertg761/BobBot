package com.bobbot.ui.bots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bobbot.data.model.Bot
import com.bobbot.data.repo.BotsRepository
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.components.EmptyState
import com.bobbot.ui.components.LoadingRow
import com.bobbot.ui.components.Pill
import com.bobbot.ui.components.StatusDot
import com.bobbot.data.repo.botName
import com.bobbot.ui.theme.BobColors
import com.bobbot.ui.theme.botColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BotsUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

@HiltViewModel
class BotsViewModel @Inject constructor(private val repo: BotsRepository) : ViewModel() {

    private val _ui = MutableStateFlow(BotsUiState())
    val ui: StateFlow<BotsUiState> = _ui.asStateFlow()
    val bots: StateFlow<List<Bot>> = repo.bots

    fun refresh() {
        if (_ui.value.loading) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val err = try {
                repo.refresh()
                null
            } catch (e: Exception) {
                e.message ?: "Could not load bots"
            }
            _ui.update { it.copy(loading = false, error = err, loaded = true) }
        }
    }

    fun refreshOnce() {
        if (!_ui.value.loaded) refresh()
    }
}

@Composable
fun BotsScreen(
    onOpenBot: (name: String) -> Unit,
    onNewBot: () -> Unit,
    onChat: (profile: String) -> Unit,
) {
    val vm: BotsViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val bots by vm.bots.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshOnce() }

    Scaffold(
        containerColor = BobColors.Bg,
        contentColor = BobColors.Text,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewBot,
                containerColor = BobColors.Accent,
                contentColor = BobColors.Bg,
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New bot", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Bots", style = MaterialTheme.typography.displaySmall, color = BobColors.Text)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Tap a bot to continue your conversation",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BobColors.TextFaint,
                        )
                    }
                    IconButton(onClick = { vm.refresh() }, enabled = !ui.loading) {
                        if (ui.loading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BobColors.Accent)
                        } else {
                            Icon(Icons.Outlined.Refresh, "Refresh", tint = BobColors.TextMuted)
                        }
                    }
                }
            }

            val err = ui.error
            if (err != null) {
                item(key = "error") { BotsErrorCard(err) { vm.refresh() } }
            }

            if (bots.isEmpty()) {
                item(key = "empty") {
                    when {
                        ui.loading -> LoadingRow("Loading bots…")
                        err != null -> Spacer(Modifier.height(0.dp))
                        else -> EmptyState(
                            title = "No bots yet",
                            subtitle = "Create your first bot to give it a persona, a model and its own chats.",
                            icon = Icons.Outlined.SmartToy,
                        )
                    }
                }
            } else {
                items(bots.size, key = { i -> bots[i].name }) { i ->
                    val bot = bots[i]
                    BotCard(bot = bot, onOpen = { onOpenBot(bot.name) }, onChat = { onChat(bot.name) })
                }
            }
        }
    }
}

@Composable
private fun BotsErrorCard(message: String, onRetry: () -> Unit) {
    BobCard(container = BobColors.RoseSoft, border = BobColors.Rose.copy(alpha = 0.4f)) {
        Text("Could not load bots", style = MaterialTheme.typography.titleSmall, color = BobColors.Rose)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = BobColors.TextMuted)
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onRetry) { Text("Retry", color = BobColors.Accent) }
    }
}

@Composable
private fun BotCard(bot: Bot, onOpen: () -> Unit, onChat: () -> Unit) {
    val accent = botColor(bot.name)
    BobCard(onClick = onChat) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(bot.name, size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        botName(bot.name),
                        style = MaterialTheme.typography.titleMedium,
                        color = BobColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (bot.isDefault) Pill("Main bot", color = accent)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    modelLine(bot),
                    style = MaterialTheme.typography.bodySmall,
                    color = BobColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (bot.description.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                bot.description,
                style = MaterialTheme.typography.bodyMedium,
                color = BobColors.TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("${bot.skillCount} skill${if (bot.skillCount == 1) "" else "s"}", color = BobColors.TextMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(bot.gatewayRunning)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (bot.gatewayRunning) "Gateway up" else "Gateway down",
                        style = MaterialTheme.typography.labelMedium,
                        color = BobColors.TextFaint,
                    )
                }
            }
            Box(Modifier.weight(1f))
            IconButton(onClick = onOpen) { Icon(Icons.Outlined.Settings, "Bot settings", tint = BobColors.TextMuted) }
            Button(
                onClick = onChat,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = BobColors.Bg),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Outlined.Forum, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Chat", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun modelLine(bot: Bot): String {
    val model = bot.model.ifBlank { "no model set" }
    val provider = bot.provider
    return if (provider.isBlank()) model else "$model · $provider"
}
