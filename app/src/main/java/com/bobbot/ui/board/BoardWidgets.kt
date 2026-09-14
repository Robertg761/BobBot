package com.bobbot.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import com.bobbot.ui.theme.BobColors
import com.mikepenz.markdown.m3.Markdown

/**
 * Small building blocks shared by the board, relay and automations screens.
 * Hand-rolled rather than pulled from experimental Material3 APIs so the surface stays stable.
 */

/** A pill-shaped segmented control. Simple, stable, and themed to the dark palette. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(BobColors.Surface)
            .border(1.dp, BobColors.OutlineSoft, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (active) BobColors.AccentSoft else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) BobColors.Accent else BobColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Labelled text field with the app's dark styling. */
@Composable
fun BobTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    minLines: Int = 1,
    maxLines: Int = if (minLines > 1) 8 else 1,
    enabled: Boolean = true,
) {
    val lines = maxLines.coerceAtLeast(minLines)
    val hint = placeholder
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = if (hint != null) ({ Text(hint, color = BobColors.TextFaint) }) else null,
        singleLine = minLines == 1 && lines == 1,
        minLines = minLines,
        maxLines = lines,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = BobColors.Text,
            unfocusedTextColor = BobColors.Text,
            focusedBorderColor = BobColors.Accent,
            unfocusedBorderColor = BobColors.Outline,
            focusedLabelColor = BobColors.Accent,
            unfocusedLabelColor = BobColors.TextFaint,
            cursorColor = BobColors.Accent,
            focusedContainerColor = BobColors.SurfaceRaised,
            unfocusedContainerColor = BobColors.SurfaceRaised,
            disabledContainerColor = BobColors.Surface,
        ),
    )
}

/** A labelled dropdown built from a clickable row + DropdownMenu (no experimental APIs). */
@Composable
fun PickerField(
    label: String,
    value: String?,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select…",
    enabled: Boolean = true,
    leading: (@Composable (String) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.small
    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = BobColors.TextFaint)
        Spacer(Modifier.height(6.dp))
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(BobColors.SurfaceRaised)
                    .border(1.dp, BobColors.Outline, shape)
                    .clickable(enabled = enabled && options.isNotEmpty()) { open = true }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (value != null && leading != null) {
                    leading(value)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    value ?: placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (value != null) BobColors.Text else BobColors.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Outlined.ArrowDropDown, null, tint = BobColors.TextMuted, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, style = MaterialTheme.typography.bodyMedium) },
                        onClick = { onSelect(option); open = false },
                    )
                }
            }
        }
    }
}

/**
 * Markdown body with a plain-text fallback. The renderer cannot be wrapped in try/catch
 * (the Compose compiler forbids it around composable calls), so unsuitable content —
 * empty, enormous, or line-limited — goes straight to Text instead.
 */
@Composable
fun MarkdownBody(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = BobColors.Text,
    maxLines: Int = Int.MAX_VALUE,
) {
    val renderable = remember(text, maxLines) {
        maxLines == Int.MAX_VALUE && text.isNotBlank() && text.length < 40_000
    }
    if (renderable) {
        Markdown(content = text, modifier = modifier.fillMaxWidth())
    } else {
        Text(
            text.trim(),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}
