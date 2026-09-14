package com.bobbot.ui.team

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobbot.core.net.*
import com.bobbot.data.repo.botName
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.theme.BobColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject

@HiltViewModel
class TeamViewModel @Inject constructor(private val api: HermesApi) : ViewModel() {
    val data = MutableStateFlow<JsonElement?>(null)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    private val path = "/api/plugins/bobbot-team"
    suspend fun refresh() {
        try { data.value = api.http.get("$path/team"); error.value = null }
        catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error.value = if (e is HermesHttpException && e.code == 404) "The BobBot team extension is not installed on this Hermes server." else e.message
        }
    }
    fun bootstrap(profile: String) = act { api.http.post("$path/profiles/$profile/bootstrap", jsonOf()) }
    fun configure(authority: String, enabled: Boolean) = act { api.http.put("$path/team", jsonOf("authority" to authority, "enabled" to enabled)) }
    fun decide(id: String, choice: String) = act {
        api.http.post("$path/requests/$id/decide", jsonOf("choice" to choice, "reason" to "Reviewed by Robert in BobBot"))
    }
    private fun act(work: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { work(); refresh() }
            catch (e: Exception) { error.value = e.message }
            finally { busy.value = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(onBack: () -> Unit, onChat: (String) -> Unit) {
    val vm: TeamViewModel = hiltViewModel()
    val data by vm.data.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { while (true) { vm.refresh(); delay(5000) } }
    val authority = data.str("authority") ?: "default"
    val enabled = data.bool("enabled") ?: true
    Scaffold(containerColor = BobColors.Bg, topBar = {
        TopAppBar(title = { Text("Team & permissions") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { item { Text(it, color = BobColors.Rose) } }
            if (data != null) {
                item { BobCard {
                    Text("Authority: ${botName(authority)}", style = MaterialTheme.typography.titleLarge)
                    Text("Assigns work, reviews results, and decides specialist permission requests.")
                    TextButton(onClick = { onChat(authority) }) { Text("Talk to ${botName(authority)}") }
                    Row { Text("Require specialist review", Modifier.weight(1f)); Switch(enabled, { vm.configure(authority, it) }, enabled = !busy) }
                    Text("Approved actions still follow Hermes' own permission rules. Direct chats retry after approval; blocked tasks can resume on the server.", style = MaterialTheme.typography.bodySmall)
                    data.list("profiles").forEach { profile ->
                        val name = profile.str("name") ?: return@forEach
                        TextButton(onClick = { vm.bootstrap(name) }, enabled = !busy) { Text("Configure ${botName(name)} for the team") }
                        if (name != authority) TextButton(onClick = { vm.configure(name, enabled) }, enabled = !busy) { Text("Make ${botName(name)} authority") }
                    }
                } }
                item { Text("Permission history", style = MaterialTheme.typography.titleLarge) }
                if (data.list("requests").isEmpty()) item { Text("No permission requests yet.") }
                items(data.list("requests"), key = { it.str("id") ?: it.toString() }) { request ->
                    BobCard {
                        Text("${botName(request.str("profile") ?: "default")} · ${request.str("tool")}", style = MaterialTheme.typography.titleMedium)
                        Text(request.str("status")?.replace('_', ' ') ?: "", color = BobColors.Accent)
                        Text(request.str("args") ?: "", style = MaterialTheme.typography.bodySmall)
                        request.str("reason")?.takeIf { it.isNotBlank() }?.let { Text(it) }
                        if (request.str("status") in listOf("pending", "needs_user")) Row {
                            Button(onClick = { vm.decide(request.str("id")!!, "approved") }, enabled = !busy) { Text("Allow once") }
                            TextButton(onClick = { vm.decide(request.str("id")!!, "denied") }, enabled = !busy) { Text("Deny") }
                        }
                    }
                }
            }
        }
    }
}
