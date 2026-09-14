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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.bobbot.data.model.ModelEntry
import com.bobbot.data.model.ModelProvider
import com.bobbot.data.repo.BotsRepository
import com.bobbot.data.repo.ModelCatalog
import com.bobbot.data.repo.ModelsRepository
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.BotAvatar
import com.bobbot.ui.components.EmptyState
import com.bobbot.ui.components.KeyValueRow
import com.bobbot.ui.components.LoadingRow
import com.bobbot.ui.components.Pill
import com.bobbot.ui.components.SectionHeader
import com.bobbot.ui.models.ModelPickerSheet
import com.bobbot.ui.models.ModelRow
import com.bobbot.ui.models.ProviderGroupHeader
import com.bobbot.ui.models.modelSpec
import com.bobbot.ui.models.orderedProviders
import com.bobbot.ui.theme.BobColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private val NAME_RE = Regex("[a-z0-9][a-z0-9_-]{0,63}")
private val RESERVED = setOf("hermes", "test", "tmp", "root", "sudo")

/** null = valid. */
internal fun botNameError(name: String): String? = when {
    name.isBlank() -> "Pick a name"
    !NAME_RE.matches(name) -> "Lowercase letters, digits, - and _ only (max 64), starting with a letter or digit"
    name in RESERVED -> "\"$name\" is reserved by Hermes"
    else -> null
}

data class PersonaTemplate(val label: String, val body: String)

internal val personaTemplates: List<PersonaTemplate> = listOf(
    PersonaTemplate(
        "Grok-style witty",
        """
        # Persona

        You are quick, funny and allergic to filler. You answer first, joke second,
        and never pad a reply to look busy.

        ## Voice
        - Dry wit, light sarcasm, never mean.
        - Short sentences. One idea per line.
        - If a question is silly, answer it anyway — then say why it was silly.

        ## Rules
        - Never open with "Certainly" or "Great question".
        - Admit uncertainty in one clause, then give your best guess.
        """.trimIndent(),
    ),
    PersonaTemplate(
        "Focused coder",
        """
        # Persona

        You are a senior engineer pairing over chat. You read the code before you
        talk about it, and you prefer a working diff to a lecture.

        ## Voice
        - Precise, concrete, no hand-waving.
        - Lead with the change, then the reasoning.

        ## Rules
        - Show code, not pseudo-code, and keep it runnable.
        - Name the file and the function you are touching.
        - Call out risk and edge cases in one short list at the end.
        """.trimIndent(),
    ),
    PersonaTemplate(
        "Research analyst",
        """
        # Persona

        You research carefully and report like an analyst: claim, evidence, confidence.

        ## Voice
        - Neutral and structured. Bullets over paragraphs.
        - Separate what is known from what you inferred.

        ## Rules
        - Always give sources or say "no source — inference".
        - Quantify when you can; flag stale data.
        - End with the two things that would change your conclusion.
        """.trimIndent(),
    ),
    PersonaTemplate(
        "Home assistant",
        """
        # Persona

        You run the house: reminders, schedules, devices, small errands.
        Calm, brief, and useful before you are charming.

        ## Voice
        - Friendly, plain language, no jargon.
        - Confirm actions in one line: what you did and when it happens.

        ## Rules
        - Ask only when something is genuinely ambiguous, then pick a sensible default.
        - Surface conflicts in the schedule before they bite.
        - Never act on anything destructive without a clear yes.
        """.trimIndent(),
    ),
)

data class NewBotUiState(
    val step: Int = 0,
    val name: String = "",
    val description: String = "",
    val soul: String = "",
    val provider: String = "",
    val model: String = "",
    val catalog: ModelCatalog? = null,
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val creating: Boolean = false,
    val error: String? = null,
    val createdName: String? = null,
) {
    val nameError: String? get() = botNameError(name)
    val hasModel: Boolean get() = provider.isNotBlank() && model.isNotBlank()
}

@HiltViewModel
class NewBotViewModel @Inject constructor(
    private val bots: BotsRepository,
    private val models: ModelsRepository,
    private val roster: com.bobbot.data.repo.RosterRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(NewBotUiState())
    val ui: StateFlow<NewBotUiState> = _ui.asStateFlow()

    fun setName(v: String) = _ui.update { it.copy(name = v.lowercase().trim(), error = null) }
    fun setDescription(v: String) = _ui.update { it.copy(description = v) }
    fun setSoul(v: String) = _ui.update { it.copy(soul = v) }
    fun setModel(provider: String, model: String) = _ui.update { it.copy(provider = provider, model = model, error = null) }
    fun goTo(step: Int) = _ui.update { it.copy(step = step.coerceIn(0, 2), error = null) }

    fun loadCatalog() {
        val st = _ui.value
        if (st.catalogLoading || st.catalog != null) return
        viewModelScope.launch {
            _ui.update { it.copy(catalogLoading = true, catalogError = null) }
            try {
                val c = models.refresh(includeUnconfigured = true)
                val pick = suggestDefault(c)
                _ui.update {
                    it.copy(
                        catalog = c,
                        catalogLoading = false,
                        provider = if (it.provider.isBlank()) pick?.first.orEmpty() else it.provider,
                        model = if (it.model.isBlank()) pick?.second.orEmpty() else it.model,
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(catalogLoading = false, catalogError = e.message ?: "Could not load models") }
            }
        }
    }

    private fun suggestDefault(c: ModelCatalog): Pair<String, String>? {
        if (c.currentProvider.isNotBlank() && c.currentModel.isNotBlank()) return c.currentProvider to c.currentModel
        val p = orderedProviders(c).firstOrNull { it.authenticated != false && it.models.isNotEmpty() } ?: return null
        val m = p.featured.firstOrNull { id -> p.models.any { it.id == id } } ?: p.models.firstOrNull()?.id ?: return null
        return p.slug to m
    }

    fun create(onCreated: (String) -> Unit) {
        val st = _ui.value
        val err = botNameError(st.name)
        if (err != null) {
            _ui.update { it.copy(step = 0, error = err) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(creating = true, error = null) }
            try {
                bots.create(
                    name = st.name,
                    description = st.description.trim(),
                    provider = st.provider.takeIf { it.isNotBlank() },
                    model = st.model.takeIf { it.isNotBlank() },
                    cloneFrom = "default",
                    soul = st.soul.takeIf { it.isNotBlank() },
                )
                // New bots join the teammate roster right away; a failure here is not worth blocking creation.
                runCatching { roster.setTeammateMessaging(st.name, true, st.description.trim()) }
                _ui.update { it.copy(creating = false, createdName = st.name) }
                onCreated(st.name)
            } catch (e: Exception) {
                _ui.update { it.copy(creating = false, error = e.message ?: "Could not create the bot") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewBotScreen(onBack: () -> Unit, onCreated: (name: String) -> Unit) {
    val vm: NewBotViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(ui.step) { if (ui.step >= 1) vm.loadCatalog() }

    val stepTitles = listOf("Identity", "Model", "Review")
    val canAdvance = when (ui.step) {
        0 -> ui.nameError == null
        1 -> ui.hasModel || ui.catalogError != null
        else -> true
    }

    Scaffold(
        containerColor = BobColors.Bg,
        contentColor = BobColors.Text,
        topBar = {
            TopAppBar(
                title = { Text("New bot") },
                navigationIcon = {
                    IconButton(onClick = { if (ui.step == 0) onBack() else vm.goTo(ui.step - 1) }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = BobColors.Text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BobColors.Bg),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Step ${ui.step + 1} of 3 · ${stepTitles[ui.step]}",
                        style = MaterialTheme.typography.labelMedium,
                        color = BobColors.TextMuted,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (ui.step + 1) / 3f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = BobColors.Accent,
                    trackColor = BobColors.SurfaceHigh,
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ui.error?.let { err ->
                    item(key = "err") {
                        BobCard(container = BobColors.RoseSoft, border = BobColors.Rose.copy(alpha = 0.4f)) {
                            Text(err, style = MaterialTheme.typography.bodyMedium, color = BobColors.Text)
                        }
                    }
                }
                when (ui.step) {
                    0 -> identityStep(ui, vm)
                    1 -> modelStep(ui, vm) { showPicker = true }
                    else -> reviewStep(ui)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ui.step > 0) {
                    OutlinedButton(
                        onClick = { vm.goTo(ui.step - 1) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text("Back") }
                }
                Button(
                    onClick = { if (ui.step < 2) vm.goTo(ui.step + 1) else vm.create(onCreated) },
                    enabled = canAdvance && !ui.creating,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BobColors.Accent, contentColor = BobColors.Bg),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    modifier = Modifier.weight(if (ui.step > 0) 1.6f else 1f),
                ) {
                    if (ui.creating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BobColors.Bg)
                        Spacer(Modifier.width(10.dp))
                        Text("Creating…")
                    } else {
                        Text(if (ui.step < 2) "Continue" else "Create bot", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }

    if (showPicker) {
        ModelPickerSheet(
            catalog = ui.catalog,
            current = modelSpec(ui.provider, ui.model),
            onDismiss = { showPicker = false },
            onPick = { provider, model ->
                showPicker = false
                vm.setModel(provider, model)
            },
        )
    }
}

private fun LazyListScope.identityStep(ui: NewBotUiState, vm: NewBotViewModel) {
    item(key = "identity") {
        BobCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BotAvatar(ui.name.ifBlank { "new" }, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Who is this bot?", style = MaterialTheme.typography.titleMedium, color = BobColors.Text)
                    Text(
                        "The name becomes the Hermes profile id.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BobColors.TextFaint,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = ui.name,
                onValueChange = vm::setName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name") },
                singleLine = true,
                isError = ui.name.isNotBlank() && ui.nameError != null,
                shape = RoundedCornerShape(14.dp),
                supportingText = {
                    Text(
                        if (ui.name.isBlank()) {
                            "Lowercase letters, digits, - and _"
                        } else {
                            ui.nameError ?: "Looks good"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ui.name.isNotBlank() && ui.nameError == null) BobColors.Mint else BobColors.TextFaint,
                    )
                },
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = ui.description,
                onValueChange = vm::setDescription,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Description") },
                placeholder = { Text("One line about what it is for") },
                shape = RoundedCornerShape(14.dp),
                minLines = 2,
            )
        }
    }
    item(key = "persona-header") { SectionHeader("Persona (SOUL.md)") }
    item(key = "templates") {
        BobCard {
            Text(
                "Start from a template, then edit freely.",
                style = MaterialTheme.typography.bodySmall,
                color = BobColors.TextFaint,
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                personaTemplates.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { t ->
                            Box(Modifier.weight(1f)) {
                                AssistChip(
                                    onClick = { vm.setSoul(t.body) },
                                    label = { Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = ui.soul,
                onValueChange = vm::setSoul,
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                placeholder = { Text("Tone, priorities, boundaries…", color = BobColors.TextFaint) },
                shape = RoundedCornerShape(14.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun LazyListScope.modelStep(
    ui: NewBotUiState,
    vm: NewBotViewModel,
    onBrowseAll: () -> Unit,
) {
    item(key = "model-current") {
        BobCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Memory, null, tint = BobColors.Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ui.model.ifBlank { "No model selected" },
                        style = MaterialTheme.typography.titleSmall,
                        color = BobColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        ui.provider.ifBlank { "pick a provider below" },
                        style = MaterialTheme.typography.bodySmall,
                        color = BobColors.TextFaint,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onBrowseAll,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Search, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Browse all models")
            }
        }
    }

    val catalog = ui.catalog
    if (catalog == null) {
        item(key = "model-loading") {
            BobCard {
                if (ui.catalogError != null) {
                    Text(ui.catalogError, style = MaterialTheme.typography.bodyMedium, color = BobColors.Rose)
                } else {
                    LoadingRow("Loading providers…")
                }
            }
        }
        return
    }

    val groups: List<Pair<ModelProvider, List<ModelEntry>>> = orderedProviders(catalog).map { p ->
        val featured = p.models.filter { p.featured.contains(it.id) }
        p to (featured.ifEmpty { p.models }).take(6)
    }.filter { it.second.isNotEmpty() }

    if (groups.isEmpty()) {
        item(key = "model-empty") {
            BobCard { EmptyState("No providers", "Connect a provider on the server first.", Icons.Outlined.SmartToy) }
        }
        return
    }

    groups.forEach { (provider, entries) ->
        item(key = "ph-" + provider.slug) { ProviderGroupHeader(provider) }
        items(entries.size, key = { i -> "m-" + provider.slug + "-" + entries[i].id }) { i ->
            val entry = entries[i]
            ModelRow(
                provider = provider,
                entry = entry,
                selected = ui.provider == provider.slug && ui.model == entry.id,
                onClick = { vm.setModel(provider.slug, entry.id) },
            )
        }
    }
}

private fun LazyListScope.reviewStep(ui: NewBotUiState) {
    item(key = "review") {
        BobCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BotAvatar(ui.name.ifBlank { "new" }, size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ui.name.ifBlank { "unnamed" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = BobColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Pill("Cloned from default", color = BobColors.Mint, icon = Icons.Outlined.Check)
                }
            }
            Spacer(Modifier.height(14.dp))
            KeyValueRow("Model", ui.model.ifBlank { "server default" })
            KeyValueRow("Provider", ui.provider.ifBlank { "server default" })
            KeyValueRow("Persona", if (ui.soul.isBlank()) "none" else "${ui.soul.length} chars")
        }
    }
    item(key = "review-desc") {
        BobCard {
            Text("Description", style = MaterialTheme.typography.labelMedium, color = BobColors.TextFaint)
            Spacer(Modifier.height(6.dp))
            Text(
                ui.description.ifBlank { "No description — you can add one later." },
                style = MaterialTheme.typography.bodyMedium,
                color = BobColors.TextMuted,
            )
        }
    }
    if (ui.soul.isNotBlank()) {
        item(key = "review-soul") {
            BobCard {
                Text("Persona preview", style = MaterialTheme.typography.labelMedium, color = BobColors.TextFaint)
                Spacer(Modifier.height(6.dp))
                Text(
                    ui.soul,
                    style = MaterialTheme.typography.bodySmall,
                    color = BobColors.TextMuted,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
