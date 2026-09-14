package com.bobbot.ui.models

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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.bobbot.core.net.child
import com.bobbot.core.net.list
import com.bobbot.core.net.obj
import com.bobbot.core.net.str
import com.bobbot.data.repo.ModelCatalog
import com.bobbot.data.repo.ModelsRepository
import com.bobbot.data.repo.SetModelResult
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.EmptyState
import com.bobbot.ui.components.LoadingRow
import com.bobbot.ui.components.Pill
import com.bobbot.ui.components.SectionHeader
import com.bobbot.ui.theme.BobColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject

data class AuxModelRow(val task: String, val provider: String, val model: String)

data class MoaSummary(val active: String, val default: String, val presetCount: Int)

/** `{tasks:[{task, provider, model}], main:{provider, model}}` — main first, then each task. */
internal fun parseAuxiliary(j: JsonElement?): List<AuxModelRow> {
    if (j == null) return emptyList()
    val out = mutableListOf<AuxModelRow>()
    val main = j.child("main")
    if (main != null) {
        out += AuxModelRow("main", main.str("provider").orEmpty(), main.str("model").orEmpty())
    }
    val tasks = j.list("tasks").ifEmpty { j.list("auxiliary") }.ifEmpty { j.arr?.toList() ?: emptyList() }
    tasks.forEach { t ->
        val task = t.str("task") ?: t.str("name") ?: return@forEach
        out += AuxModelRow(task, t.str("provider").orEmpty(), t.str("model").orEmpty())
    }
    return out
}

internal fun parseMoa(j: JsonElement?): MoaSummary? {
    if (j == null) return null
    val presets = j.child("presets")
    val count = presets.obj?.size ?: presets.arr?.size ?: 0
    val active = j.str("active_preset").orEmpty()
    val default = j.str("default_preset").orEmpty()
    if (count == 0 && active.isBlank() && default.isBlank()) return null
    return MoaSummary(active = active, default = default, presetCount = count)
}

data class ModelsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val catalog: ModelCatalog? = null,
    val aux: List<AuxModelRow> = emptyList(),
    val auxError: String? = null,
    val moa: MoaSummary? = null,
    val applying: Boolean = false,
    val notice: String? = null,
    val pending: PendingModel? = null,
)

/** A tap awaiting confirmation. [serverMessage] set = the server asked for the expensive-model confirm. */
data class PendingModel(
    val provider: String,
    val model: String,
    val task: String? = null,
    val serverMessage: String? = null,
)

@HiltViewModel
class ModelsViewModel @Inject constructor(private val models: ModelsRepository) : ViewModel() {

    private val _ui = MutableStateFlow(ModelsUiState())
    val ui: StateFlow<ModelsUiState> = _ui.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val c = models.refresh(includeUnconfigured = true)
                _ui.update { it.copy(catalog = c, loading = false) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "Could not load models") }
            }
            loadAuxiliary()
            loadMoa()
        }
    }

    private var started = false

    fun loadOnce() {
        if (started) return
        started = true
        load()
    }

    private suspend fun loadAuxiliary() {
        try {
            _ui.update { it.copy(aux = parseAuxiliary(models.auxiliary()), auxError = null) }
        } catch (e: Exception) {
            _ui.update { it.copy(auxError = e.message ?: "Could not load auxiliary models") }
        }
    }

    private suspend fun loadMoa() {
        try {
            _ui.update { it.copy(moa = parseMoa(models.moa())) }
        } catch (_: Exception) {
            _ui.update { it.copy(moa = null) }
        }
    }

    fun ask(provider: String, model: String, task: String? = null) =
        _ui.update { it.copy(pending = PendingModel(provider, model, task)) }

    fun dismissPending() = _ui.update { it.copy(pending = null) }

    fun clearNotice() = _ui.update { it.copy(notice = null) }

    /** Applies the pending pick. [confirm] re-sends after the server asked for confirmation. */
    fun applyPending(confirm: Boolean = false) {
        val p = _ui.value.pending ?: return
        viewModelScope.launch {
            _ui.update { it.copy(applying = true, pending = if (confirm) null else it.pending) }
            if (p.task != null) {
                try {
                    models.setAuxiliary(p.task, p.provider, p.model)
                    loadAuxiliary()
                    _ui.update { it.copy(applying = false, pending = null, notice = "${p.task} now uses ${p.model}") }
                } catch (e: Exception) {
                    _ui.update { it.copy(applying = false, pending = null, notice = e.message ?: "Could not set the model") }
                }
                return@launch
            }
            when (val r = models.setGlobal(p.provider, p.model, confirm = confirm)) {
                is SetModelResult.Ok -> {
                    try {
                        val c = models.refresh(includeUnconfigured = true)
                        _ui.update { it.copy(catalog = c) }
                    } catch (_: Exception) {
                    }
                    loadAuxiliary()
                    _ui.update { it.copy(applying = false, pending = null, notice = "Default model is now ${p.model}") }
                }
                is SetModelResult.ConfirmRequired -> _ui.update {
                    it.copy(applying = false, pending = p.copy(serverMessage = r.message))
                }
                is SetModelResult.Error -> _ui.update {
                    it.copy(applying = false, pending = null, notice = r.message)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(onBack: () -> Unit) {
    val vm: ModelsViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var auxPickerTask by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.loadOnce() }

    val catalog = ui.catalog

    Scaffold(
        containerColor = BobColors.Bg,
        contentColor = BobColors.Text,
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = BobColors.Text)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.load() }) {
                        if (ui.loading || ui.applying) {
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
                item(key = "notice") {
                    BobCard(container = BobColors.SurfaceHigh, padding = PaddingValues(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(notice, style = MaterialTheme.typography.bodyMedium, color = BobColors.Text, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.clearNotice() }) {
                                Icon(Icons.Outlined.Close, "Dismiss", tint = BobColors.TextFaint)
                            }
                        }
                    }
                }
            }
            ui.error?.let { err ->
                item(key = "error") {
                    BobCard(container = BobColors.RoseSoft, border = BobColors.Rose.copy(alpha = 0.4f)) {
                        Text(err, style = MaterialTheme.typography.bodyMedium, color = BobColors.Text)
                        TextButton(onClick = { vm.load() }) { Text("Retry", color = BobColors.Accent) }
                    }
                }
            }

            item(key = "current") {
                BobCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Memory, null, tint = BobColors.Accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Default model", style = MaterialTheme.typography.labelMedium, color = BobColors.TextFaint)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                catalog?.currentModel?.takeIf { it.isNotBlank() } ?: "unknown",
                                style = MaterialTheme.typography.titleMedium,
                                color = BobColors.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                catalog?.currentProvider?.takeIf { it.isNotBlank() } ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = BobColors.TextMuted,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Applies to new sessions. Each bot can override it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BobColors.TextFaint,
                    )
                }
            }

            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    leadingIcon = { Icon(Icons.Outlined.Search, null, tint = BobColors.TextFaint) },
                    placeholder = { Text("Search models or providers") },
                )
            }

            if (catalog == null) {
                item(key = "loading") {
                    if (ui.loading) BobCard { LoadingRow("Loading models…") } else Spacer(Modifier.height(0.dp))
                }
            } else {
                val current = modelSpec(catalog.currentProvider, catalog.currentModel)
                val groups = orderedProviders(catalog)
                    .map { p -> p to p.models.filter { matchesQuery(p, it, query) } }
                    .filter { it.second.isNotEmpty() }

                if (groups.isEmpty()) {
                    item(key = "no-models") {
                        BobCard {
                            EmptyState(
                                title = if (query.isBlank()) "No models" else "No matches",
                                subtitle = if (query.isBlank()) "No provider is connected." else "Try a different search.",
                                icon = Icons.Outlined.Memory,
                            )
                        }
                    }
                } else {
                    groups.forEach { (provider, entries) ->
                        item(key = "hdr-" + provider.slug) { ProviderGroupHeader(provider) }
                        items(entries.size, key = { i -> "m-" + provider.slug + "-" + entries[i].id }) { i ->
                            val entry = entries[i]
                            ModelRow(
                                provider = provider,
                                entry = entry,
                                selected = isCurrentModel(current, provider.slug, entry.id),
                                onClick = { vm.ask(provider.slug, entry.id) },
                            )
                        }
                    }
                }
            }

            item(key = "aux-header") { SectionHeader("Auxiliary models") }
            item(key = "aux") {
                BobCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Tune, null, tint = BobColors.Violet, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Models used for background tasks",
                            style = MaterialTheme.typography.bodySmall,
                            color = BobColors.TextFaint,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    when {
                        ui.auxError != null -> Text(
                            ui.auxError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BobColors.Rose,
                        )
                        ui.aux.isEmpty() -> Text(
                            "No auxiliary models configured.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BobColors.TextFaint,
                        )
                        else -> ui.aux.forEach { row ->
                            AuxRowItem(row) { auxPickerTask = row.task }
                        }
                    }
                }
            }

            item(key = "moa-header") { SectionHeader("Mixture of agents") }
            item(key = "moa") {
                BobCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Hub, null, tint = BobColors.Mint, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            val moa = ui.moa
                            if (moa == null) {
                                Text("Not configured", style = MaterialTheme.typography.titleSmall, color = BobColors.TextMuted)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "No MoA presets reported by the server.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BobColors.TextFaint,
                                )
                            } else {
                                Text(
                                    moa.active.ifBlank { moa.default.ifBlank { "no active preset" } },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = BobColors.Text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    "default: ${moa.default.ifBlank { "—" }} · ${moa.presetCount} preset" +
                                        if (moa.presetCount == 1) "" else "s",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BobColors.TextFaint,
                                )
                            }
                        }
                        if (ui.moa != null) Pill("Read-only", color = BobColors.TextMuted)
                    }
                }
            }
        }
    }

    val pending = ui.pending
    if (pending != null && pending.task == null) {
        val server = pending.serverMessage
        AlertDialog(
            onDismissRequest = { vm.dismissPending() },
            containerColor = BobColors.SurfaceRaised,
            titleContentColor = BobColors.Text,
            textContentColor = BobColors.TextMuted,
            icon = if (server != null) {
                ({ Icon(Icons.Outlined.Warning, null, tint = BobColors.Amber) })
            } else {
                null
            },
            title = { Text(if (server != null) "Confirm model" else "Switch default model?") },
            text = {
                Text(server ?: "New sessions will use ${pending.model} on ${pending.provider}.")
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.applyPending(confirm = server != null) },
                    enabled = !ui.applying,
                ) { Text(if (server != null) "Yes, use it" else "Switch", color = BobColors.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissPending() }) { Text("Cancel", color = BobColors.TextMuted) }
            },
        )
    }

    val task = auxPickerTask
    if (task != null) {
        val row = ui.aux.firstOrNull { it.task == task }
        ModelPickerSheet(
            catalog = catalog,
            current = modelSpec(row?.provider.orEmpty(), row?.model.orEmpty()),
            onDismiss = { auxPickerTask = null },
            onPick = { provider, model ->
                auxPickerTask = null
                vm.ask(provider, model, task)
                vm.applyPending()
            },
        )
    }
}

@Composable
private fun AuxRowItem(row: AuxModelRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.task.replace('_', ' '),
                style = MaterialTheme.typography.bodyMedium,
                color = BobColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                row.model.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = BobColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (row.provider.isNotBlank()) Pill(row.provider, color = BobColors.TextMuted)
        Box(Modifier.width(4.dp))
        TextButton(onClick = onClick) { Text("Change", style = MaterialTheme.typography.labelMedium, color = BobColors.Accent) }
    }
}
