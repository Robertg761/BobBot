package com.bobbot.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bobbot.data.model.Bot
import com.bobbot.data.repo.BotsRepository
import com.bobbot.data.repo.GroupsRepository
import com.bobbot.data.repo.botName
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.setup.fieldColors
import com.bobbot.ui.theme.BobColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class NewGroupUi(val bots: List<Bot> = emptyList(), val creating: Boolean = false, val error: String? = null)

@HiltViewModel
class NewGroupViewModel @Inject constructor(private val bots: BotsRepository, private val groups: GroupsRepository) : ViewModel() {
    private val _ui = MutableStateFlow(NewGroupUi())
    val ui: StateFlow<NewGroupUi> = _ui
    private var attempt: Pair<List<String>, String>? = null

    fun load() {
        viewModelScope.launch {
            val list = bots.bots.value.ifEmpty { runCatching { bots.refresh() }.getOrDefault(emptyList()) }
            _ui.update { it.copy(bots = list) }
        }
    }

    fun create(profiles: List<String>, name: String, onCreated: (String) -> Unit) {
        if (_ui.value.creating) return
        viewModelScope.launch {
            _ui.update { it.copy(creating = true, error = null) }
            try {
                // The same id is reused on retry so a flaky network can't make two rooms.
                val id = attempt?.takeIf { it.first == profiles }?.second ?: UUID.randomUUID().toString().also { attempt = profiles to it }
                groups.create(id, profiles, name)
                attempt = null
                _ui.update { it.copy(creating = false) }
                onCreated(id)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _ui.update { it.copy(creating = false, error = e.message ?: "Could not create the group") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupScreen(onBack: () -> Unit, onCreated: (roomId: String) -> Unit) {
    val vm: NewGroupViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    var chosen by rememberSaveable { mutableStateOf(listOf<String>()) }
    var name by rememberSaveable { mutableStateOf("") }

    Scaffold(
        containerColor = BobColors.Bg,
        topBar = {
            TopAppBar(
                title = { Text("New group") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BobColors.Bg),
            )
        },
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                ui.error?.let { Text(it, color = BobColors.Rose, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
                Button(
                    onClick = { vm.create(chosen, name, onCreated) },
                    enabled = !ui.creating && chosen.size in 2..6,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BobColors.Accent, contentColor = BobColors.Bg),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) { Text(if (ui.creating) "Creating…" else if (chosen.size < 2) "Pick at least two bots" else "Start group with ${chosen.size} bots") }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("Group name (optional)") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Choose two to six bots. They share this conversation and can hand work to each other.", style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted)
                Spacer(Modifier.height(8.dp))
            }
            items(ui.bots, key = { it.name }) { bot ->
                val selected = bot.name in chosen
                Row(
                    Modifier.fillMaxWidth().clickable { chosen = if (selected) chosen - bot.name else if (chosen.size < 6) chosen + bot.name else chosen }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BotAvatar(bot.name, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(botName(bot.name), style = MaterialTheme.typography.titleSmall, color = BobColors.Text)
                        if (bot.description.isNotBlank()) Text(bot.description, style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted, maxLines = 1)
                    }
                    Checkbox(checked = selected, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = BobColors.Accent, checkmarkColor = BobColors.Bg))
                }
            }
            if (ui.bots.size < 2) item { Text("Create another bot first to start a group.", color = BobColors.TextFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp)) }
        }
    }
}
