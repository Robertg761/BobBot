package com.bobbot.ui.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobbot.core.net.asString
import com.bobbot.core.net.child
import com.bobbot.core.net.dbl
import com.bobbot.core.net.int
import com.bobbot.core.net.list
import com.bobbot.core.net.str
import com.bobbot.data.model.ServerStatus
import com.bobbot.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.util.Locale
import javax.inject.Inject

data class HostStats(
    val hostname: String = "",
    val os: String = "",
    val cpu: Double? = null,
    val memory: Double? = null,
    val disk: Double? = null,
    val uptime: String = "",
)

data class SystemUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val status: ServerStatus? = null,
    val host: HostStats? = null,
    val hostError: String? = null,
    val usage: List<Pair<String, String>> = emptyList(),
    val usageError: String? = null,
    val logFile: String = "agent",
    val logLevel: String? = null,
    val logLines: List<String> = emptyList(),
    val logsLoading: Boolean = false,
    val logsError: String? = null,
    val gatewayBusy: String? = null,
    val gatewayMessage: String? = null,
    val gatewayFailed: Boolean = false,
)

@HiltViewModel
class SystemViewModel @Inject constructor(
    private val system: SystemRepository,
) : ViewModel() {

    companion object {
        val LOG_FILES = listOf("agent", "errors", "gateway")
        val LOG_LEVELS = listOf<String?>(null, "ERROR", "WARNING", "INFO", "DEBUG")
        private const val LOG_LINES = 100
    }

    private val _state = MutableStateFlow(SystemUiState())
    val state: StateFlow<SystemUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, hostError = null, usageError = null) }

            try {
                val st = system.refreshStatus()
                _state.update { it.copy(status = st) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Could not reach the server") }
            }

            try {
                _state.update { it.copy(host = parseStats(system.stats())) }
            } catch (e: Exception) {
                _state.update { it.copy(hostError = e.message ?: "Host stats unavailable") }
            }

            try {
                _state.update { it.copy(usage = parseUsage(system.usage(30))) }
            } catch (e: Exception) {
                _state.update { it.copy(usageError = e.message ?: "Usage unavailable") }
            }

            _state.update { it.copy(loading = false) }
            loadLogs()
        }
    }

    // ---- logs ----

    fun setLogFile(file: String) {
        _state.update { it.copy(logFile = file) }
        loadLogs()
    }

    fun setLogLevel(level: String?) {
        _state.update { it.copy(logLevel = level) }
        loadLogs()
    }

    fun loadLogs() {
        viewModelScope.launch {
            val s = _state.value
            _state.update { it.copy(logsLoading = true, logsError = null) }
            try {
                val r = system.logs(s.logFile, LOG_LINES, s.logLevel)
                _state.update { it.copy(logsLoading = false, logLines = parseLogLines(r)) }
            } catch (e: Exception) {
                _state.update { it.copy(logsLoading = false, logsError = e.message ?: "Could not read logs", logLines = emptyList()) }
            }
        }
    }

    // ---- gateway ----

    fun gateway(action: String) {
        viewModelScope.launch {
            _state.update { it.copy(gatewayBusy = action, gatewayMessage = null, gatewayFailed = false) }
            try {
                val r = system.gateway(action)
                val msg = r.str("message") ?: r.str("detail") ?: r.str("status") ?: "Gateway $action requested"
                _state.update { it.copy(gatewayBusy = null, gatewayMessage = msg, gatewayFailed = false) }
                runCatching { system.refreshStatus() }.onSuccess { st -> _state.update { it.copy(status = st) } }
            } catch (e: Exception) {
                _state.update { it.copy(gatewayBusy = null, gatewayFailed = true, gatewayMessage = e.message ?: "Gateway $action failed") }
            }
        }
    }

    // ---- parsing (every shape here is best-effort) ----

    private fun parseStats(j: JsonElement?): HostStats {
        val cpu = j.dbl("cpu_percent") ?: j.child("cpu").dbl("percent") ?: j.child("cpu").dbl("usage")
        val mem = j.child("memory").dbl("percent") ?: j.dbl("memory_percent") ?: j.child("mem").dbl("percent")
        val disk = j.child("disk").dbl("percent") ?: j.dbl("disk_percent")
        val uptime = j.dbl("uptime_seconds") ?: j.dbl("uptime") ?: j.child("host").dbl("uptime_seconds")
        return HostStats(
            hostname = j.str("hostname") ?: j.child("host").str("hostname") ?: "",
            os = j.str("os") ?: j.str("platform") ?: j.child("host").str("os") ?: "",
            cpu = cpu,
            memory = mem,
            disk = disk,
            uptime = uptime?.let { humanizeUptime(it.toLong()) } ?: "",
        )
    }

    private fun parseUsage(j: JsonElement?): List<Pair<String, String>> {
        val roots = listOfNotNull(j, j.child("summary"), j.child("totals"), j.child("usage"))
        fun num(vararg keys: String): Double? =
            roots.firstNotNullOfOrNull { r -> keys.firstNotNullOfOrNull { k -> r.dbl(k) } }
        fun count(vararg keys: String): Int? =
            roots.firstNotNullOfOrNull { r -> keys.firstNotNullOfOrNull { k -> r.int(k) } }

        val rows = mutableListOf<Pair<String, String>>()
        num("total_tokens", "tokens")?.let { rows += "Total tokens" to formatCount(it) }
        num("input_tokens", "prompt_tokens")?.let { rows += "Input tokens" to formatCount(it) }
        num("output_tokens", "completion_tokens")?.let { rows += "Output tokens" to formatCount(it) }
        num("cost_usd", "total_cost", "cost")?.let { rows += "Cost (USD)" to String.format(Locale.US, "%.2f", it) }
        count("sessions", "session_count", "total_sessions")?.let { rows += "Sessions" to "$it" }
        count("messages", "message_count")?.let { rows += "Messages" to "$it" }
        return rows
    }

    private fun parseLogLines(j: JsonElement?): List<String> {
        val raw = j.list("lines").ifEmpty { j.list("logs").ifEmpty { j.list("entries") } }
        if (raw.isNotEmpty()) {
            return raw.mapNotNull { e ->
                e.str("line") ?: e.str("text") ?: e.str("message") ?: e.asString()
            }.filter { it.isNotBlank() }
        }
        val blob = j.str("content") ?: j.str("text") ?: return emptyList()
        return blob.lines().filter { it.isNotBlank() }
    }

    private fun formatCount(v: Double): String =
        if (v >= 1_000_000) String.format(Locale.US, "%.1fM", v / 1_000_000)
        else String.format(Locale.US, "%,d", v.toLong())

    private fun humanizeUptime(seconds: Long): String {
        if (seconds <= 0) return "—"
        val d = seconds / 86_400
        val h = (seconds % 86_400) / 3_600
        val m = (seconds % 3_600) / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }
}
