package com.bobbot.ui.update

import android.app.DownloadManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bobbot.ui.components.BobCard
import com.bobbot.ui.components.Pill
import com.bobbot.ui.theme.BobColors
import com.bobbot.update.ApkUpdateManager
import com.bobbot.update.UpdateInfo
import kotlinx.coroutines.delay

private enum class Stage { Available, Downloading, PermissionRequired, ReadyToInstall, Error }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(info: UpdateInfo, manager: ApkUpdateManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lifecycleOwner = LocalLifecycleOwner.current
    var stage by remember { mutableStateOf(Stage.Available) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notesOpen by remember { mutableStateOf(false) }

    fun recompute() {
        val q = manager.queryDownload(context)
        stage = when {
            manager.hasDownloadedApkForVersion(context, info.latestVersionName) ->
                if (manager.needsUnknownSourcesPermission(context)) Stage.PermissionRequired else Stage.ReadyToInstall
            q?.status == DownloadManager.STATUS_RUNNING || q?.status == DownloadManager.STATUS_PENDING || q?.status == DownloadManager.STATUS_PAUSED -> Stage.Downloading
            q?.status == DownloadManager.STATUS_FAILED -> Stage.Error
            else -> Stage.Available
        }
    }

    LaunchedEffect(info.latestVersionName) { recompute() }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) recompute() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(stage) {
        if (stage != Stage.Downloading) return@LaunchedEffect
        while (stage == Stage.Downloading) {
            val q = manager.queryDownload(context)
            if (q == null) { stage = Stage.Available; break }
            when (q.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    progress = 1f; progressText = "Download complete"
                    stage = if (manager.needsUnknownSourcesPermission(context)) Stage.PermissionRequired else Stage.ReadyToInstall
                    break
                }
                DownloadManager.STATUS_FAILED -> { error = "Download failed (code ${q.reason ?: "?"}). Please try again."; stage = Stage.Error; break }
                else -> {
                    progress = if (q.totalBytes > 0) (q.bytesDownloaded.toFloat() / q.totalBytes).coerceIn(0f, 1f) else null
                    progressText = if (q.totalBytes > 0) "Downloading… ${(q.bytesDownloaded * 100 / q.totalBytes).coerceIn(0, 100)}%" else "Downloading…"
                }
            }
            delay(700)
        }
    }

    fun startDownload() { error = null; progress = null; progressText = null; manager.startDownload(context, info); stage = Stage.Downloading }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = BobColors.SurfaceRaised) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(BobColors.AccentSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.SystemUpdate, null, tint = BobColors.Accent)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Update BobBot", style = MaterialTheme.typography.titleLarge, color = BobColors.Text)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("v${info.latestVersionName}", color = BobColors.Mint)
                        info.apkSizeBytes?.let { Text("${it / 1_048_576} MB", style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint) }
                    }
                }
            }
            if (info.releaseNotes.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                BobCard(container = BobColors.Surface) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("What's new", style = MaterialTheme.typography.titleSmall, color = BobColors.Text, modifier = Modifier.weight(1f))
                        TextButton(onClick = { notesOpen = !notesOpen }) { Text(if (notesOpen) "Less" else "More", color = BobColors.Accent) }
                    }
                    Text(info.releaseNotes, style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted, maxLines = if (notesOpen) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(18.dp))
            when (stage) {
                Stage.Available -> {
                    Text("The update is downloaded straight from BobBot's GitHub releases. Android will show an install prompt.", style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted)
                    Spacer(Modifier.height(14.dp))
                    Actions(secondary = "Not now" to onDismiss, primary = "Download" to ::startDownload)
                }
                Stage.Downloading -> {
                    Text(progressText ?: "Downloading…", color = BobColors.Text, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    val p = progress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = BobColors.Accent, trackColor = BobColors.SurfaceHigh)
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = BobColors.Accent, trackColor = BobColors.SurfaceHigh)
                    Spacer(Modifier.height(14.dp))
                    Actions(secondary = "Close" to onDismiss, primary = null)
                }
                Stage.PermissionRequired -> {
                    Text("Android needs a one-time permission to let BobBot install updates.", color = BobColors.Text, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Tap Open settings, enable “Allow from this source”, then come back here to install.", style = MaterialTheme.typography.bodySmall, color = BobColors.TextMuted)
                    Spacer(Modifier.height(14.dp))
                    Actions(secondary = "Later" to onDismiss, primary = "Open settings" to { context.startActivity(manager.buildUnknownSourcesSettingsIntent(context)) })
                }
                Stage.ReadyToInstall -> {
                    Text("Ready to install. Android will show an install prompt.", color = BobColors.Text, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                    Actions(secondary = "Close" to onDismiss, primary = "Install" to {
                        if (manager.needsUnknownSourcesPermission(context)) { stage = Stage.PermissionRequired; return@to }
                        val intent = manager.buildInstallIntent(context)
                        if (intent == null) { error = "Couldn't start the installer. Please download again."; stage = Stage.Error } else context.startActivity(intent)
                    })
                }
                Stage.Error -> {
                    Text(error ?: "Something went wrong.", color = BobColors.Rose, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                    Actions(secondary = "Close" to onDismiss, primary = "Try again" to ::startDownload)
                }
            }
        }
    }
}

@Composable
private fun Actions(secondary: Pair<String, () -> Unit>, primary: Pair<String, () -> Unit>?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = secondary.second) { Text(secondary.first, color = BobColors.TextMuted) }
        if (primary != null) {
            Spacer(Modifier.width(10.dp))
            Button(onClick = primary.second, colors = ButtonDefaults.buttonColors(containerColor = BobColors.Accent, contentColor = BobColors.Bg)) {
                Text(primary.first, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
