package com.bobbot.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bobbot.data.prefs.AppPrefs
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.theme.BobColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Battery optimisation is the single biggest reason the background link dies on Samsung: One UI
 * puts "sleeping" apps to sleep and the socket goes with them. Exempting BobBot is the fix, and
 * only the user can grant it.
 */

fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(context.packageName)
}.getOrDefault(true)

/**
 * Ask for the exemption. Some builds (and some work profiles) refuse the direct request, so fall
 * back to the system list where the user can pick BobBot by hand.
 */
@SuppressLint("BatteryLife")
fun requestBatteryExemption(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData("package:${context.packageName}".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(direct)
    } catch (e: Exception) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** Re-read on every resume: the user grants this in system settings, outside our process. */
@Composable
fun rememberBatteryExempt(): Boolean {
    val ctx = LocalContext.current
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(ctx)) }
    LifecycleResumeEffect(Unit) {
        exempt = isIgnoringBatteryOptimizations(ctx)
        onPauseOrDispose { }
    }
    return exempt
}

/** The settings row: state plus the one button that can change it. */
@Composable
fun BatteryOptimisationRow() {
    val ctx = LocalContext.current
    val exempt = rememberBatteryExempt()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Battery optimisation", color = BobColors.Text, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (exempt) "Exempt · Android will leave the link running" else "Not exempt · Android may stop the link",
                color = if (exempt) BobColors.Mint else BobColors.TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (!exempt) TextButton(onClick = { requestBatteryExemption(ctx) }) { Text("Allow in background") }
    }
}

/**
 * A banner for the inbox, shown once. [onDismiss] is expected to remember the answer so it does
 * not come back; see [BatteryExemptionBanner] for the wired-up version.
 */
@Composable
fun BatteryExemptionCard(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    BobCard {
        Text("Keep BobBot listening", color = BobColors.Text, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Android puts sleeping apps to sleep, and your bots' messages stop arriving. Letting BobBot run in the background keeps the link to your server open.",
            color = BobColors.TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { requestBatteryExemption(ctx); onDismiss() }) { Text("Allow in background") }
            TextButton(onClick = onDismiss) { Text("Not now", color = BobColors.TextMuted) }
        }
    }
}

/**
 * Drop-in banner: decides for itself whether the prompt is due, and remembers the answer.
 * Renders nothing when BobBot is already exempt, notifications are off, or the user has answered.
 */
@Composable
fun BatteryExemptionBanner(vm: BatteryPromptViewModel = hiltViewModel()) {
    val due by vm.due.collectAsStateWithLifecycle()
    val exempt = rememberBatteryExempt()
    if (!due || exempt) return
    BatteryExemptionCard(onDismiss = vm::dismiss)
}

@HiltViewModel
class BatteryPromptViewModel @Inject constructor(private val prefs: AppPrefs) : ViewModel() {
    private val _due = MutableStateFlow(false)
    val due: StateFlow<Boolean> = _due

    init {
        viewModelScope.launch { _due.value = runCatching { prefs.shouldShowBatteryPrompt() }.getOrDefault(false) }
    }

    fun dismiss() {
        _due.value = false
        viewModelScope.launch { runCatching { prefs.markBatteryPromptShown() } }
    }
}
