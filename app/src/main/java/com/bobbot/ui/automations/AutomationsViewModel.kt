package com.bobbot.ui.automations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobbot.core.net.jsonOf
import com.bobbot.data.model.CronJob
import com.bobbot.data.model.CronRun
import com.bobbot.data.repo.AutomationsRepository
import com.bobbot.data.repo.BotsRepository
import com.bobbot.data.repo.DeliveryTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AutomationsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val jobs: List<CronJob> = emptyList(),
    val expanded: String? = null,
    val runs: Map<String, List<CronRun>> = emptyMap(),
    val runsLoading: Set<String> = emptySet(),
    val runsError: Map<String, String> = emptyMap(),
    val deliveryTargets: List<DeliveryTarget> = emptyList(),
    val botNames: List<String> = emptyList(),
    val busy: Set<String> = emptySet(),
    val notice: String? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
)

@HiltViewModel
class AutomationsViewModel @Inject constructor(
    private val automations: AutomationsRepository,
    private val bots: BotsRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(AutomationsUiState())
    val ui: StateFlow<AutomationsUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh(quiet: Boolean = false) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = it.jobs.isEmpty() && !quiet, refreshing = !quiet, error = null) }
            try {
                val jobs = automations.refresh()
                _ui.update { it.copy(loading = false, refreshing = false, jobs = jobs) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, refreshing = false, error = e.message ?: "Could not load automations") }
            }
            loadPickers()
        }
    }

    private suspend fun loadPickers() {
        val targets = runCatching { automations.deliveryTargets() }.getOrDefault(emptyList())
        // ntfy is the push-to-this-phone target and local is always valid, so make sure both are offered.
        val merged = buildList {
            // Bot chats first: that is where results are read in this app.
            addAll(targets.filter { it.id.startsWith("bot-chat:") }.map { t ->
                val p = t.id.removePrefix("bot-chat:")
                DeliveryTarget(t.id, "${com.bobbot.data.repo.BotNames.display(p)}'s chat (in BobBot)")
            })
            addAll(targets.filterNot { it.id.startsWith("bot-chat:") })
            if (targets.none { it.id == "ntfy" }) add(DeliveryTarget("ntfy", "ntfy (push to this phone)"))
            if (targets.none { it.id == "local" }) add(DeliveryTarget("local", "Local (no delivery)"))
        }
        val names = bots.bots.value.ifEmpty { runCatching { bots.refresh() }.getOrDefault(emptyList()) }.map { it.name }
        _ui.update { it.copy(deliveryTargets = merged, botNames = names.ifEmpty { listOf("default") }) }
    }

    fun toggleExpanded(job: CronJob) {
        val open = _ui.value.expanded == job.id
        _ui.update { it.copy(expanded = if (open) null else job.id) }
        if (!open && _ui.value.runs[job.id] == null) loadRuns(job)
    }

    fun loadRuns(job: CronJob) {
        _ui.update { it.copy(runsLoading = it.runsLoading + job.id, runsError = it.runsError - job.id) }
        viewModelScope.launch {
            try {
                val runs = automations.runs(job)
                _ui.update { it.copy(runs = it.runs + (job.id to runs), runsLoading = it.runsLoading - job.id) }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        runsLoading = it.runsLoading - job.id,
                        runsError = it.runsError + (job.id to (e.message ?: "Could not load runs")),
                    )
                }
            }
        }
    }

    fun setEnabled(job: CronJob, enabled: Boolean) = act(job, if (enabled) "Resumed" else "Paused") {
        if (enabled) automations.resume(job) else automations.pause(job)
    }

    fun trigger(job: CronJob) = act(job, "Run started") {
        automations.trigger(job)
        loadRuns(job)
    }

    fun delete(job: CronJob) = act(job, "Deleted") { automations.delete(job) }

    private fun act(job: CronJob, success: String, block: suspend () -> Unit) {
        _ui.update { it.copy(busy = it.busy + job.id, error = null) }
        viewModelScope.launch {
            try {
                block()
                _ui.update { it.copy(busy = it.busy - job.id, notice = success, jobs = automations.jobs.value) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = it.busy - job.id, error = e.message ?: "That did not work") }
            }
        }
    }

    fun create(name: String, prompt: String, schedule: String, deliver: String, profile: String?, onDone: () -> Unit) {
        if (name.isBlank() || prompt.isBlank() || schedule.isBlank()) return
        _ui.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                automations.create(name.trim(), prompt.trim(), schedule.trim(), deliver, profile)
                _ui.update { it.copy(saving = false, jobs = automations.jobs.value, notice = "Automation created") }
                onDone()
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, saveError = e.message ?: "Could not create the automation") }
            }
        }
    }

    fun update(job: CronJob, name: String, prompt: String, schedule: String, deliver: String, onDone: () -> Unit) {
        _ui.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                automations.update(
                    job,
                    jsonOf(
                        "name" to name.trim(),
                        "prompt" to prompt.trim(),
                        "schedule" to schedule.trim(),
                        "deliver" to deliver,
                    ),
                )
                _ui.update { it.copy(saving = false, jobs = automations.jobs.value, notice = "Automation updated") }
                onDone()
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, saveError = e.message ?: "Could not save the automation") }
            }
        }
    }

    fun clearNotice() = _ui.update { it.copy(notice = null) }
    fun clearSaveError() = _ui.update { it.copy(saveError = null) }
}
