package com.bobbot.data.repo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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
        personas.update { it + (profile to (heading?.trim().orEmpty())) }
        recompute()
    }

    fun setNicknames(map: Map<String, String>) { nicknames.value = map; recompute() }

    /** Seed from a persisted snapshot so notifications name bots correctly before any network call. */
    fun seed(map: Map<String, String>) { personas.update { map + it }; recompute() }

    /** Persona headings only (nicknames live in their own preference). */
    fun personaSnapshot(): Map<String, String> = personas.value.filterValues { it.isNotBlank() }

    fun display(profile: String): String = _names.value[profile]?.takeIf { it.isNotBlank() } ?: fallback(profile)

    private fun recompute() {
        val out = mutableMapOf<String, String>()
        for ((p, h) in personas.value) if (h.isNotBlank()) out[p] = h
        for ((p, n) in nicknames.value) if (n.isNotBlank()) out[p] = n
        _names.value = out
    }

    /** "research-bot" reads as "Research Bot"; profile ids are lowercase with dashes and underscores. */
    fun fallback(profile: String): String = profile.split('-', '_').filter { it.isNotBlank() }.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }.ifBlank { profile }

    /** Only a document title can name a bot. Template section labels are not names. */
    fun headingOf(soul: String?): String? = soul?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotBlank() }
        ?.takeIf { it.startsWith("# ") }
        ?.removePrefix("# ")?.trim()
        ?.takeIf { it.isNotBlank() && it.length <= 40 && !isGenericHeading(it) }

    internal fun isGenericHeading(heading: String): Boolean = heading.lowercase() in setOf(
        "persona", "soul", "soul.md", "identity", "personality", "instructions", "voice", "rules",
    )
}

/** Display name for a profile, recomposing when names load or change. */
@Composable
fun botName(profile: String): String {
    val names by BotNames.names.collectAsState()
    return names[profile]?.takeIf { it.isNotBlank() } ?: BotNames.fallback(profile)
}

@Composable
fun botNamesState(): State<Map<String, String>> = BotNames.names.collectAsState()
