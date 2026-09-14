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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bobbot.core.net.arr
import com.bobbot.core.net.asString
import com.bobbot.core.net.bool
import com.bobbot.core.net.child
import com.bobbot.core.net.obj
import com.bobbot.core.net.str
import com.bobbot.data.model.Bot
import com.bobbot.data.model.SessionSummary
import com.bobbot.data.repo.BotsRepository
import com.bobbot.data.repo.ModelCatalog
import com.bobbot.data.repo.ModelsRepository
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.components.EmptyState
import com.bobbot.ui.components.LoadingRow
import com.bobbot.ui.components.Pill
import com.bobbot.ui.components.SectionHeader
import com.bobbot.ui.models.ModelPickerSheet
import com.bobbot.ui.models.modelSpec
import com.bobbot.data.repo.botName
import com.bobbot.ui.theme.BobColors
import com.bobbot.ui.theme.botColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import kotlin.math.abs

data class SkillItem(
    val name: String,
    val description: String,
    val enabled: Boolean,
    val category: String?,
)

data class BotDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val bot: Bot? = null,
    val soul: String = "",
    val soulDraft: String = "",
    val soulLoading: Boolean = false,
    val soulSaving: Boolean = false,
    val descDraft: String = "",
    val descSaving: Boolean = false,
    val describing: Boolean = false,
    val skills: List<SkillItem> = emptyList(),
    val skillsLoading: Boolean = false,
    val skillsError: String? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val sessionsLoading: Boolean = false,
    val sessionsError: String? = null,
    val catalog: ModelCatalog? = null,
    val catalogLoading: Boolean = false,
    val applyingModel: Boolean = false,
    val busy: Boolean = false,
    val notice: String? = null,
    val gone: Boolean = false,
) {
    val soulDirty: Boolean get() = soulDraft != soul
}

/** The skills payload shape varies by server build; accept array, {skills:[…]} and {skills:{name:{…}}}. */
internal fun parseSkills(j: JsonElement?): List<SkillItem> {
    if (j == null) return emptyList()
    val array = j.arr
        ?: j.child("skills").arr
        ?: j.child("items").arr
        ?: j.child("available").arr
    if (array != null) return array.mapNotNull { skillFrom(it, null) }
    val map = j.child("skills").obj ?: return emptyList()
    return map.entries.mapNotNull { (key, value) -> skillFrom(value, key) }
}

private fun skillFrom(e: JsonElement, key: String?): SkillItem? {
    if (e is JsonPrimitive) {
        val n = e.asString()?.takeIf { it.isNotBlank() } ?: return null
        return SkillItem(name = key ?: n, description = if (key != null) n else "", enabled = true, category = null)
    }
    if (e !is JsonObject) return null
    val name = e.str("name") ?: e.str("id") ?: e.str("slug") ?: key ?: return null
    return SkillItem(
        name = name,
        description = e.str("description") ?: e.str("summary") ?: e.str("detail") ?: "",
        enabled = e.bool("enabled") ?: e.bool("active") ?: e.bool("is_enabled") ?: true,
        category = e.str("category") ?: e.str("group") ?: e.str("source"),
    )
}

@HiltViewModel
class BotDetailViewModel @Inject constructor(
    private val bots: BotsRepository,
    private val models: ModelsRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(BotDetailUiState())
    val ui: StateFlow<BotDetailUiState> = _ui.asStateFlow()

    private var name: String = ""

    fun bind(botName: String) {
        if (name == botName) return
        name = botName
        reload()
    }

    fun reload() {
        val n = name.ifBlank { return }
        viewModelScope.launch {
            _ui.update {
                it.copy(loading = true, error = null, soulLoading = true, skillsLoading = true, sessionsLoading = true)
            }
            val cached = bots.cached(n)
            if (cached != null) _ui.update { it.copy(bot = cached, descDraft = cached.description) }
            try {
                val list = bots.refresh()
                val bot = list.firstOrNull { it.name == n }
                _ui.update {
                    it.copy(
                        loading = false,
                        bot = bot ?: it.bot,
                        descDraft = bot?.description ?: it.descDraft,
                        error = if (bot == null) "Bot \"$n\" was not found" else null,
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "Could not load this bot") }
            }
            loadSoul()
            loadSkills()
            loadSessions()
        }
    }

    private suspend fun loadSoul() {
        try {
            val s = bots.soul(name)
            _ui.update { it.copy(soul = s, soulDraft = if (it.soulDirty) it.soulDraft else s, soulLoading = false) }
        } catch (e: Exception) {
            _ui.update { it.copy(soulLoading = false, notice = e.message ?: "Could not load the persona") }
        }
    }

    private suspend fun loadSkills() {
        try {
            val parsed = parseSkills(bots.skills(name))
            _ui.update { it.copy(skills = parsed.sortedBy { s -> s.name.lowercase() }, skillsLoading = false, skillsError = null) }
        } catch (e: Exception) {
            _ui.update { it.copy(skillsLoading = false, skillsError = e.message ?: "Could not load skills") }
        }
    }

    private suspend fun loadSessions() {
        try {
            val s = bots.sessions(name)
            _ui.update { it.copy(sessions = s, sessionsLoading = false, sessionsError = null) }
        } catch (e: Exception) {
            _ui.update { it.copy(sessionsLoading = false, sessionsError = e.message ?: "Could not load chats") }
        }
    }

    fun onSoulChange(v: String) = _ui.update { it.copy(soulDraft = v) }

    fun saveSoul() {
        val draft = _ui.value.soulDraft
        viewModelScope.launch {
            _ui.update { it.copy(soulSaving = true) }
            try {
                bots.setSoul(name, draft)
                _ui.update { it.copy(soul = draft, soulSaving = false, notice = "Persona saved") }
            } catch (e: Exception) {
                _ui.update { it.copy(soulSaving = false, notice = e.message ?: "Could not save the persona") }
            }
        }
    }

    fun onDescChange(v: String) = _ui.update { it.copy(descDraft = v) }

    fun saveDescription(onDone: () -> Unit = {}) {
        val d = _ui.value.descDraft.trim()
        viewModelScope.launch {
            _ui.update { it.copy(descSaving = true) }
            try {
                bots.setDescription(name, d)
                _ui.update { it.copy(descSaving = false, bot = bots.cached(name) ?: it.bot, notice = "Description saved") }
                onDone()
            } catch (e: Exception) {
                _ui.update { it.copy(descSaving = false, notice = e.message ?: "Could not save the description") }
            }
        }
    }

    fun autoDescribe() {
        viewModelScope.launch {
            _ui.update { it.copy(describing = true) }
            try {
                bots.describeAuto(name)
                val bot = bots.cached(name)
                _ui.update {
                    it.copy(
                        describing = false,
                        bot = bot ?: it.bot,
                        descDraft = bot?.description ?: it.descDraft,
                        notice = "Description written by the bot",
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(describing = false, notice = e.message ?: "Auto-describe failed") }
            }
        }
    }

    fun loadCatalog() {
        if (_ui.value.catalogLoading) return
        viewModelScope.launch {
            _ui.update { it.copy(catalogLoading = true) }
            try {
                val c = models.refresh(profile = name)
                _ui.update { it.copy(catalog = c, catalogLoading = false) }
            } catch (e: Exception) {
                _ui.update { it.copy(catalogLoading = false, notice = e.message ?: "Could not load models") }
            }
        }
    }

    fun applyModel(provider: String, model: String) {
        viewModelScope.launch {
            _ui.update { it.copy(applyingModel = true) }
            try {
                bots.setModel(name, provider, model)
                _ui.update {
                    it.copy(applyingModel = false, bot = bots.cached(name) ?: it.bot, notice = "Model set to $model")
                }
            } catch (e: Exception) {
                _ui.update { it.copy(applyingModel = false, notice = e.message ?: "Could not set the model") }
            }
        }
    }

    fun toggleSkill(skill: SkillItem, enabled: Boolean) {
        _ui.update { st -> st.copy(skills = st.skills.map { if (it.name == skill.name) it.copy(enabled = enabled) else it }) }
        viewModelScope.launch {
            try {
                bots.toggleSkill(name, skill.name, enabled)
            } catch (e: Exception) {
                _ui.update { st ->
                    st.copy(
                        skills = st.skills.map { if (it.name == skill.name) it.copy(enabled = !enabled) else it },
                        notice = e.message ?: "Could not toggle ${skill.name}",
                    )
                }
            }
        }
    }

    fun setNickname(nickname: String) {
        viewModelScope.launch {
            runCatching { bots.setNickname(name, nickname) }
                .onFailure { e -> _ui.update { it.copy(notice = e.message ?: "Could not save display name") } }
        }
    }

    fun rename(newName: String, onDone: () -> Unit) {
        val target = newName.trim()
        if (target.isBlank() || target == name) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                bots.rename(name, target)
                _ui.update { it.copy(busy = false) }
                onDone()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, notice = e.message ?: "Rename failed") }
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        if (_ui.value.bot?.isDefault == true) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                bots.delete(name)
                _ui.update { it.copy(busy = false, gone = true) }
                onDone()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, notice = e.message ?: "Delete failed") }
            }
        }
    }

    fun clearNotice() = _ui.update { it.copy(notice = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotDetailScreen(
    name: String,
    onBack: () -> Unit,
    onChat: (profile: String) -> Unit,
    onOpenSession: (sessionId: String, profile: String) -> Unit,
) {
    val vm: BotDetailViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(name) { vm.bind(name) }

    var showPicker by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showNickname by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var editingDesc by remember { mutableStateOf(false) }

    val bot = ui.bot
    val accent = botColor(name)

    Scaffold(
        containerColor = BobColors.Bg,
        contentColor = BobColors.Text,
        topBar = {
            TopAppBar(
                title = { Text(botName(name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = BobColors.Text)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.reload() }) {
                        if (ui.loading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BobColors.Accent)
                        } else {
                            Icon(Icons.Outlined.Refresh, "Refresh", tint = BobColors.TextMuted)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BobColors.Bg),
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.notice?.let { notice ->
                item(key = "notice") { NoticeBar(notice) { vm.clearNotice() } }
            }
            ui.error?.let { err ->
                item(key = "error") {
                    BobCard(container = BobColors.RoseSoft, border = BobColors.Rose.copy(alpha = 0.4f)) {
                        Text(err, style = MaterialTheme.typography.bodyMedium, color = BobColors.Text)
                        TextButton(onClick = { vm.reload() }) { Text("Retry", color = BobColors.Accent) }
                    }
                }
            }

            item(key = "header") {
                HeaderCard(
                    name = name,
                    bot = bot,
                    ui = ui,
                    editing = editingDesc,
                    onEdit = { editingDesc = true },
                    onCancelEdit = {
                        editingDesc = false
                        vm.onDescChange(bot?.description ?: "")
                    },
                    onDescChange = vm::onDescChange,
                    onSaveDesc = { vm.saveDescription { editingDesc = false } },
                    onAutoDescribe = { vm.autoDescribe() },
                )
            }

            item(key = "chat-cta") {
                Button(
                    onClick = { onChat(name) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = BobColors.Bg),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Icon(Icons.Outlined.Forum, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Chat with ${botName(name)}", style = MaterialTheme.typography.titleSmall)
                }
            }

            item(key = "persona-header") {
                SectionHeader("Persona", trailing = {
                    if (ui.soulDirty) Pill("Unsaved", color = BobColors.Amber)
                })
            }
            item(key = "persona") {
                PersonaCard(
                    soulDraft = ui.soulDraft,
                    loading = ui.soulLoading,
                    saving = ui.soulSaving,
                    dirty = ui.soulDirty,
                    onChange = vm::onSoulChange,
                    onSave = { vm.saveSoul() },
                )
            }

            item(key = "model-header") { SectionHeader("Model") }
            item(key = "model") {
                BobCard(onClick = {
                    showPicker = true
                    vm.loadCatalog()
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Memory, null, tint = BobColors.Accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                bot?.model?.takeIf { it.isNotBlank() } ?: "No model set",
                                style = MaterialTheme.typography.bodyLarge,
                                color = BobColors.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                bot?.provider?.takeIf { it.isNotBlank() } ?: "tap to choose a provider",
                                style = MaterialTheme.typography.bodySmall,
                                color = BobColors.TextFaint,
                            )
                        }
                        if (ui.applyingModel) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BobColors.Accent)
                        } else {
                            Icon(Icons.Outlined.ChevronRight, null, tint = BobColors.TextFaint)
                        }
                    }
                }
            }

            item(key = "skills-header") {
                SectionHeader("Skills", trailing = {
                    if (ui.skills.isNotEmpty()) {
                        Text(
                            "${ui.skills.count { it.enabled }}/${ui.skills.size} on",
                            style = MaterialTheme.typography.labelMedium,
                            color = BobColors.TextFaint,
                        )
                    }
                })
            }
            item(key = "skills") {
                BobCard(padding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)) {
                    when {
                        ui.skillsLoading -> LoadingRow("Loading skills…")
                        ui.skillsError != null -> Text(
                            ui.skillsError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BobColors.Rose,
                            modifier = Modifier.padding(12.dp),
                        )
                        ui.skills.isEmpty() -> EmptyState(
                            title = "No skills",
                            subtitle = "This bot has no skills installed.",
                            icon = Icons.Outlined.Extension,
                        )
                        else -> ui.skills.forEach { skill ->
                            SkillRow(skill) { vm.toggleSkill(skill, it) }
                        }
                    }
                }
            }

            item(key = "chats-header") { SectionHeader("Recent chats") }
            if (ui.sessionsLoading && ui.sessions.isEmpty()) {
                item(key = "chats-loading") { BobCard { LoadingRow("Loading chats…") } }
            } else if (ui.sessions.isEmpty()) {
                item(key = "chats-empty") {
                    BobCard {
                        EmptyState(
                            title = "No chats yet",
                            subtitle = ui.sessionsError ?: "Start a conversation to see it here.",
                            icon = Icons.Outlined.History,
                        )
                    }
                }
            } else {
                items(ui.sessions.size, key = { i -> "s-" + ui.sessions[i].id }) { i ->
                    val s = ui.sessions[i]
                    SessionRow(s) { onOpenSession(s.id, name) }
                }
            }

            item(key = "danger-header") { SectionHeader("Danger zone") }
            item(key = "danger") {
                BobCard(border = BobColors.Rose.copy(alpha = 0.25f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Warning, null, tint = BobColors.Amber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (bot?.isDefault == true) {
                                "This is the default bot. Hermes can't rename or delete its profile, but you can give it a display name."
                            } else {
                                "Renaming changes the profile directory. Deleting removes its persona, config and chats."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = BobColors.TextMuted,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { if (bot?.isDefault == true) showNickname = true else showRename = true },
                            enabled = !ui.busy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.DriveFileRenameOutline, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (bot?.isDefault == true) "Display name" else "Rename")
                        }
                        Button(
                            onClick = { showDelete = true },
                            enabled = !ui.busy && bot?.isDefault != true,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BobColors.RoseSoft,
                                contentColor = BobColors.Rose,
                                disabledContainerColor = BobColors.SurfaceHigh,
                                disabledContentColor = BobColors.TextFaint,
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        ModelPickerSheet(
            catalog = ui.catalog,
            current = modelSpec(bot?.provider ?: "", bot?.model ?: ""),
            onDismiss = { showPicker = false },
            onPick = { provider, model ->
                showPicker = false
                vm.applyModel(provider, model)
            },
        )
    }

    if (showNickname) {
        NicknameDialog(
            current = botName(name),
            onDismiss = { showNickname = false },
            onConfirm = { nick -> showNickname = false; vm.setNickname(nick) },
        )
    }

    if (showRename) {
        RenameDialog(
            current = name,
            busy = ui.busy,
            onDismiss = { showRename = false },
            onConfirm = { newName ->
                showRename = false
                vm.rename(newName) { onBack() }
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            containerColor = BobColors.SurfaceRaised,
            titleContentColor = BobColors.Text,
            textContentColor = BobColors.TextMuted,
            icon = { Icon(Icons.Outlined.Warning, null, tint = BobColors.Rose) },
            title = { Text("Delete $name?") },
            text = { Text("Its persona, configuration and chat history are removed from the server. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    vm.delete { onBack() }
                }) { Text("Delete", color = BobColors.Rose) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel", color = BobColors.TextMuted) }
            },
        )
    }
}

@Composable
private fun NoticeBar(text: String, onDismiss: () -> Unit) {
    BobCard(container = BobColors.SurfaceHigh, padding = PaddingValues(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = BobColors.Text, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Dismiss", tint = BobColors.TextFaint) }
        }
    }
}

@Composable
private fun HeaderCard(
    name: String,
    bot: Bot?,
    ui: BotDetailUiState,
    editing: Boolean,
    onEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onDescChange: (String) -> Unit,
    onSaveDesc: () -> Unit,
    onAutoDescribe: () -> Unit,
) {
    BobCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(name, size = 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = BobColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (bot?.isDefault == true) Pill("Default", color = botColor(name))
                }
                Spacer(Modifier.height(6.dp))
                val modelLabel = bot?.model.orEmpty()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (modelLabel.isNotBlank()) Pill(modelLabel, color = BobColors.Accent, icon = Icons.Outlined.Memory)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        if (editing) {
            OutlinedTextField(
                value = ui.descDraft,
                onValueChange = onDescChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Description") },
                shape = RoundedCornerShape(14.dp),
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onSaveDesc,
                    enabled = !ui.descSaving,
                    shape = RoundedCornerShape(12.dp),
                ) { Text(if (ui.descSaving) "Saving…" else "Save") }
                TextButton(onClick = onCancelEdit) { Text("Cancel", color = BobColors.TextMuted) }
            }
        } else {
            Text(
                bot?.description?.takeIf { it.isNotBlank() } ?: "No description yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (bot?.description.isNullOrBlank()) BobColors.TextFaint else BobColors.TextMuted,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(16.dp), tint = BobColors.Accent)
                    Spacer(Modifier.width(6.dp))
                    Text("Edit", color = BobColors.Accent)
                }
                TextButton(onClick = onAutoDescribe, enabled = !ui.describing) {
                    if (ui.describing) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = BobColors.Violet)
                    } else {
                        Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(16.dp), tint = BobColors.Violet)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (ui.describing) "Writing…" else "Auto-describe", color = BobColors.Violet)
                }
            }
        }
    }
}

@Composable
private fun PersonaCard(
    soulDraft: String,
    loading: Boolean,
    saving: Boolean,
    dirty: Boolean,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    BobCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Psychology, null, tint = BobColors.Violet, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("SOUL.md", style = MaterialTheme.typography.titleSmall, color = BobColors.Text)
            Box(Modifier.weight(1f))
            Text("${soulDraft.length} chars", style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint)
        }
        Spacer(Modifier.height(10.dp))
        if (loading) {
            LoadingRow("Loading persona…")
        } else {
            OutlinedTextField(
                value = soulDraft,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                placeholder = { Text("Who is this bot? Tone, priorities, boundaries…", color = BobColors.TextFaint) },
                shape = RoundedCornerShape(14.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onSave,
                enabled = dirty && !saving,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saving) "Saving…" else "Save persona") }
        }
    }
}

@Composable
private fun SkillRow(skill: SkillItem, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    skill.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = BobColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                skill.category?.takeIf { it.isNotBlank() }?.let { Pill(it, color = BobColors.TextMuted) }
            }
            if (skill.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = BobColors.TextFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = skill.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun SessionRow(s: SessionSummary, onClick: () -> Unit) {
    BobCard(onClick = onClick, container = BobColors.SurfaceRaised, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    s.title.ifBlank { s.preview.ifBlank { "Untitled chat" } },
                    style = MaterialTheme.typography.bodyLarge,
                    color = BobColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    sessionMeta(s),
                    style = MaterialTheme.typography.bodySmall,
                    color = BobColors.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (s.isActive) {
                Pill("Live", color = BobColors.Mint)
                Spacer(Modifier.width(8.dp))
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = BobColors.TextFaint)
        }
    }
}

@Composable
private fun RenameDialog(current: String, busy: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    val valid = value.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}")) && value != current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BobColors.SurfaceRaised,
        titleContentColor = BobColors.Text,
        textContentColor = BobColors.TextMuted,
        title = { Text("Rename bot") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.lowercase().trim() },
                    singleLine = true,
                    label = { Text("New name") },
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Lowercase letters, digits, dash and underscore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BobColors.TextFaint,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = valid && !busy) {
                Text("Rename", color = if (valid) BobColors.Accent else BobColors.TextFaint)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = BobColors.TextMuted) } },
    )
}

internal fun sessionMeta(s: SessionSummary): String {
    val parts = mutableListOf<String>()
    val when_ = relativeTime(s.lastActive)
    if (when_.isNotBlank()) parts += when_
    if (s.messageCount > 0) parts += "${s.messageCount} msg"
    if (s.model.isNotBlank()) parts += s.model
    if (s.source.isNotBlank()) parts += s.source
    return parts.joinToString(" · ")
}

/** Server timestamps are epoch seconds (occasionally millis). */
internal fun relativeTime(raw: Double): String {
    if (raw <= 0.0) return ""
    val millis = if (raw > 1_000_000_000_000.0) raw.toLong() else (raw * 1000.0).toLong()
    val diff = abs(System.currentTimeMillis() - millis) / 1000
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86_400 -> "${diff / 3600}h ago"
        diff < 2_592_000 -> "${diff / 86_400}d ago"
        else -> "${diff / 2_592_000}mo ago"
    }
}


@Composable
private fun NicknameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BobColors.SurfaceRaised,
        titleContentColor = BobColors.Text,
        textContentColor = BobColors.TextMuted,
        title = { Text("Display name") },
        text = {
            Column {
                Text(
                    "Shown everywhere in BobBot instead of the profile id. Leave it empty to fall back to the persona's heading in SOUL.md.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = BobColors.TextMuted) } },
    )
}
