package com.bobbot.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bobbot.data.model.ModelEntry
import com.bobbot.data.model.ModelProvider
import com.bobbot.data.repo.ModelCatalog
import com.bobbot.ui.components.EmptyState
import com.bobbot.ui.components.LoadingRow
import com.bobbot.ui.components.Pill
import com.bobbot.ui.theme.BobColors

/** "provider/model" is how a model is addressed everywhere in the UI. */
internal fun modelSpec(provider: String, model: String): String =
    if (provider.isBlank()) model else "$provider/$model"

/** Tolerant match: `current` may be a bare model id or a "provider/model" spec. */
internal fun isCurrentModel(current: String, provider: String, model: String): Boolean {
    if (current.isBlank() || model.isBlank()) return false
    val c = current.trim()
    return c == model || c == modelSpec(provider, model) || c.substringAfterLast('/') == model
}

/** Configured providers first, then the current one on top, then alphabetical. */
internal fun orderedProviders(catalog: ModelCatalog): List<ModelProvider> =
    catalog.providers.sortedWith(
        compareByDescending<ModelProvider> { it.isCurrent }
            .thenByDescending { it.authenticated != false }
            .thenByDescending { it.models.isNotEmpty() }
            .thenBy { it.name.lowercase() },
    )

internal fun matchesQuery(provider: ModelProvider, model: ModelEntry, q: String): Boolean {
    if (q.isBlank()) return true
    val needle = q.trim().lowercase()
    return model.id.lowercase().contains(needle) ||
        provider.name.lowercase().contains(needle) ||
        provider.slug.lowercase().contains(needle)
}

@Composable
internal fun ModelCapabilityChips(entry: ModelEntry, featured: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (featured) Pill("Featured", icon = Icons.Outlined.Star, color = BobColors.Amber)
        if (entry.fast) Pill("Fast", icon = Icons.Outlined.Bolt, color = BobColors.Mint)
        if (entry.reasoning) Pill("Reasoning", icon = Icons.Outlined.Psychology, color = BobColors.Violet)
        if (entry.free) Pill("Free", color = BobColors.Mint)
    }
}

/** Header row for a provider group inside a model list. */
@Composable
internal fun ProviderGroupHeader(provider: ModelProvider, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            provider.name.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = BobColors.TextFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (provider.isCurrent) Pill("In use", color = BobColors.Accent)
        if (provider.authenticated == false) Pill("Not connected", color = BobColors.Amber)
    }
}

@Composable
internal fun ModelRow(
    provider: ModelProvider,
    entry: ModelEntry,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    val border: Color = if (selected) BobColors.Accent.copy(alpha = 0.6f) else BobColors.OutlineSoft
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) BobColors.AccentSoft.copy(alpha = 0.5f) else BobColors.SurfaceRaised)
            .border(1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.id,
                style = MaterialTheme.typography.bodyLarge,
                color = BobColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                provider.slug,
                style = MaterialTheme.typography.bodySmall,
                color = BobColors.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val featured = provider.featured.contains(entry.id)
            if (featured || entry.fast || entry.reasoning || entry.free) {
                Spacer(Modifier.height(8.dp))
                ModelCapabilityChips(entry, featured)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Outlined.Check, null, tint = BobColors.Accent, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Shared model picker. Search + provider groups + capability chips.
 * Used by the bot detail screen, the new-bot wizard, the chat screen and the global models screen.
 *
 * When [onPickDefault] is given, every row gets a secondary "Default" action so the caller can
 * offer "set as the bot's default model" next to the primary (per-chat) pick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    catalog: ModelCatalog?,
    current: String,
    onDismiss: () -> Unit,
    onPick: (provider: String, model: String) -> Unit,
    onPickDefault: ((provider: String, model: String) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val pickDefault = onPickDefault

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BobColors.Surface,
        contentColor = BobColors.Text,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Choose a model", style = MaterialTheme.typography.titleLarge, color = BobColors.Text)
            if (current.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Current: $current",
                    style = MaterialTheme.typography.bodySmall,
                    color = BobColors.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = BobColors.TextFaint) },
                placeholder = { Text("Search models or providers") },
            )
            if (onPickDefault != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tap a model to use it here, or \"Default\" to make it this bot's default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BobColors.TextFaint,
                )
            }
            Spacer(Modifier.height(8.dp))

            if (catalog == null) {
                LoadingRow("Loading models…")
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            val groups = orderedProviders(catalog)
                .map { p -> p to p.models.filter { matchesQuery(p, it, query) } }
                .filter { it.second.isNotEmpty() }

            if (groups.isEmpty()) {
                EmptyState(
                    title = if (query.isBlank()) "No models available" else "No matches",
                    subtitle = if (query.isBlank()) "Connect a provider first." else "Try a different search.",
                )
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.forEach { (provider, models) ->
                    item(key = "hdr-" + provider.slug) { ProviderGroupHeader(provider) }
                    items(models.size, key = { i -> provider.slug + "/" + models[i].id }) { i ->
                        val entry = models[i]
                        ModelRow(
                            provider = provider,
                            entry = entry,
                            selected = isCurrentModel(current, provider.slug, entry.id),
                            onClick = { onPick(provider.slug, entry.id) },
                            trailing = if (pickDefault == null) null else ({
                                TextButton(
                                    onClick = { pickDefault(provider.slug, entry.id) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        "Default",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = BobColors.Accent,
                                    )
                                }
                            }),
                        )
                    }
                }
            }
        }
    }
}
