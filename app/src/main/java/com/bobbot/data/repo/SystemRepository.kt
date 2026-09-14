package com.bobbot.data.repo

import com.bobbot.core.net.HermesApi
import com.bobbot.data.model.MessagingPlatform
import com.bobbot.data.model.ServerStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemRepository @Inject constructor(private val api: HermesApi) {
    private val _status = MutableStateFlow<ServerStatus?>(null)
    val status: StateFlow<ServerStatus?> = _status

    suspend fun refreshStatus(): ServerStatus = api.status().also { _status.value = it }
    suspend fun stats(): JsonElement = api.systemStats()
    suspend fun usage(days: Int = 30): JsonElement = api.analyticsUsage(days)
    suspend fun modelUsage(days: Int = 30): JsonElement = api.analyticsModels(days)
    suspend fun logs(file: String, lines: Int, level: String?) = api.logs(file, lines, level)
    suspend fun gateway(action: String) = api.gatewayAction(action)
    suspend fun platforms(): List<MessagingPlatform> = api.platforms()
    suspend fun me() = runCatching { api.me() }.getOrNull()

    /**
     * Configure the ntfy platform on Hermes so bots can push to this phone.
     * Sets the topic as the home channel so cron jobs / the cronjob tool can `deliver: ntfy`.
     */
    suspend fun enableNtfy(server: String, topic: String, token: String?): JsonElement {
        val env = mutableMapOf(
            "NTFY_TOPIC" to topic,
            "NTFY_SERVER_URL" to server,
            "NTFY_HOME_CHANNEL" to topic,
            "NTFY_HOME_CHANNEL_NAME" to "BobBot",
            "NTFY_MARKDOWN" to "true",
        )
        if (!token.isNullOrBlank()) env["NTFY_TOKEN"] = token
        return api.updatePlatform("ntfy", enabled = true, env = env)
    }

    suspend fun testNtfy() = api.testPlatform("ntfy")
}
