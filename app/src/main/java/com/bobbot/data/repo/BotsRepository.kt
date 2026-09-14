package com.bobbot.data.repo

import com.bobbot.core.net.HermesApi
import com.bobbot.core.net.jsonOf
import com.bobbot.data.model.Bot
import com.bobbot.data.prefs.AppPrefs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BotsRepository @Inject constructor(private val api: HermesApi, private val prefs: AppPrefs) {
    private val _bots = MutableStateFlow<List<Bot>>(emptyList())
    val bots: StateFlow<List<Bot>> = _bots

    suspend fun refresh(): List<Bot> {
        val list = api.bots().sortedWith(compareByDescending<Bot> { it.isDefault }.thenBy { it.name.lowercase() })
        _bots.value = list
        BotNames.setNicknames(prefs.currentNicknames())
        // Persona headings ("# Clove") become the bots' display names.
        coroutineScope {
            list.map { b -> async { runCatching { api.soul(b.name) }.getOrNull()?.let { BotNames.setPersona(b.name, BotNames.headingOf(it)) } } }.awaitAll()
        }
        return list
    }

    suspend fun setNickname(profile: String, nickname: String) {
        prefs.setNickname(profile, nickname)
        BotNames.setNicknames(prefs.currentNicknames())
    }

    fun cached(name: String): Bot? = _bots.value.firstOrNull { it.name == name }

    /**
     * Create a bot. `cloneFrom` copies config/skills from an existing profile.
     * `soul` is written after creation because POST /api/profiles does not take a persona.
     */
    suspend fun create(
        name: String,
        description: String,
        provider: String?,
        model: String?,
        cloneFrom: String? = null,
        soul: String? = null,
    ): JsonElement {
        val r = api.createBot(
            jsonOf(
                "name" to name, "description" to description,
                "provider" to provider, "model" to model,
                "clone_from" to cloneFrom,
                // Omit keep_skills to retain inherited skills. Hermes expects a list, not a flag.
            ),
        )
        val persona = newBotPersona(name, description, soul)
        run {
            try { api.setSoul(name, persona) }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                throw IllegalStateException("Bot '$name' was created, but its persona could not be saved. Open its settings to finish setup: ${e.message}", e)
            }
        }
        try { api.http.post("/api/plugins/bobbot-team/profiles/$name/bootstrap", jsonOf()) }
        catch (e: com.bobbot.core.net.HermesHttpException) {
            if (e.code != 404) throw IllegalStateException("Bot '$name' was created, but team setup failed. Open Team & permissions to retry: ${e.message}", e)
        }
        refresh()
        return r
    }

    suspend fun delete(name: String) { api.deleteBot(name); refresh() }
    suspend fun rename(name: String, newName: String) { api.renameBot(name, newName); refresh() }
    suspend fun soul(name: String) = api.soul(name)
    suspend fun setSoul(name: String, content: String) = api.setSoul(name, content).also { BotNames.setPersona(name, BotNames.headingOf(content)) }
    suspend fun setDescription(name: String, d: String) { api.setBotDescription(name, d); refresh() }
    suspend fun setModel(name: String, provider: String, model: String) { api.setBotModel(name, provider, model); refresh() }
    suspend fun describeAuto(name: String) { api.describeBotAuto(name); refresh() }
    suspend fun skills(name: String) = api.skills(name.takeIf { it != "default" })
    suspend fun toggleSkill(bot: String, skill: String, enabled: Boolean) = api.toggleSkill(skill, enabled, bot.takeIf { it != "default" })
    suspend fun toolsets(name: String) = api.toolsets(name.takeIf { it != "default" })
    suspend fun sessions(name: String, limit: Int = 50) = api.sessions(name.takeIf { it != "default" }, limit)
}
