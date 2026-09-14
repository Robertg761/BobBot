package com.bobbot.data.repo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Friendly names for bots. Hermes profile ids are fixed (the default profile cannot be renamed at
 * all), but every bot has a persona whose SOUL.md usually starts with "# Name". BobBot shows that
 * name, or a local nickname the user sets, instead of the raw profile id.
 *
 * Priority: local nickname > SOUL.md heading > profile id.
 */
object BotNames {
    private val personas = MutableStateFlow<Map<String, String>>(emptyMap())
    private val nicknames = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    val names: StateFlow<Map<String, String>> = _names

    fun setPersona(profile: String, heading: String?) {
        personas.value = personas.value + (profile to (heading?.trim().orEmpty()))
        recompute()
    }

    fun setNicknames(map: Map<String, String>) { nicknames.value = map; recompute() }

    fun display(profile: String): String = _names.value[profile]?.takeIf { it.isNotBlank() } ?: profile

    private fun recompute() {
        val out = mutableMapOf<String, String>()
        for ((p, h) in personas.value) if (h.isNotBlank()) out[p] = h
        for ((p, n) in nicknames.value) if (n.isNotBlank()) out[p] = n
        _names.value = out
    }

    /** First markdown heading of a SOUL.md, e.g. "# Clove" -> "Clove". */
    fun headingOf(soul: String?): String? = soul?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("#") }
        ?.trimStart('#')?.trim()
        ?.takeIf { it.isNotBlank() && it.length <= 40 }
}

/** Display name for a profile, recomposing when names load or change. */
@Composable
fun botName(profile: String): String {
    val names by BotNames.names.collectAsState()
    return names[profile]?.takeIf { it.isNotBlank() } ?: profile
}

@Composable
fun botNamesState(): State<Map<String, String>> = BotNames.names.collectAsState()
