package com.bobbot.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bobbot_prefs")

/** Connection + auth state persisted across launches. */
data class ConnectionPrefs(
    val baseUrl: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val tokenExpiresAt: Long = 0L,
    val setupComplete: Boolean = false,
    val activeProfile: String = "default",
    val displayName: String = "",
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()
    val hasToken: Boolean get() = accessToken.isNotBlank()
}

data class NotificationPrefs(
    val enabled: Boolean = true,
    val ntfyServer: String = "https://ntfy.sh",
    val ntfyTopic: String = "",
    val ntfyToken: String = "",
    val watchBoard: Boolean = true,
    val watchCron: Boolean = true,
    val watchRelay: Boolean = true,
)

@Singleton
class AppPrefs @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val BASE_URL = stringPreferencesKey("base_url")
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
        val EXPIRES = longPreferencesKey("token_expires_at")
        val SETUP = booleanPreferencesKey("setup_complete")
        val PROFILE = stringPreferencesKey("active_profile")
        val NAME = stringPreferencesKey("display_name")
        val NOTIF = booleanPreferencesKey("notif_enabled")
        val NTFY_SERVER = stringPreferencesKey("ntfy_server")
        val NTFY_TOPIC = stringPreferencesKey("ntfy_topic")
        val NTFY_TOKEN = stringPreferencesKey("ntfy_token")
        val WATCH_BOARD = booleanPreferencesKey("watch_board")
        val WATCH_CRON = booleanPreferencesKey("watch_cron")
        val WATCH_RELAY = booleanPreferencesKey("watch_relay")
        val LAST_CRON_SEEN = stringPreferencesKey("last_cron_seen")
        val LAST_BOARD_CURSOR = longPreferencesKey("last_board_cursor")
        val NICKNAMES = stringPreferencesKey("bot_nicknames")
        val PKCE_VERIFIER = stringPreferencesKey("pkce_verifier")
        val PKCE_STATE = stringPreferencesKey("pkce_state")
    }

    val connection: Flow<ConnectionPrefs> = context.dataStore.data.map { p ->
        ConnectionPrefs(
            baseUrl = p[K.BASE_URL] ?: "",
            accessToken = p[K.ACCESS] ?: "",
            refreshToken = p[K.REFRESH] ?: "",
            tokenExpiresAt = p[K.EXPIRES] ?: 0L,
            setupComplete = p[K.SETUP] ?: false,
            activeProfile = p[K.PROFILE] ?: "default",
            displayName = p[K.NAME] ?: "",
        )
    }

    val notifications: Flow<NotificationPrefs> = context.dataStore.data.map { p ->
        NotificationPrefs(
            enabled = p[K.NOTIF] ?: true,
            ntfyServer = p[K.NTFY_SERVER] ?: "https://ntfy.sh",
            ntfyTopic = p[K.NTFY_TOPIC] ?: "",
            ntfyToken = p[K.NTFY_TOKEN] ?: "",
            watchBoard = p[K.WATCH_BOARD] ?: true,
            watchCron = p[K.WATCH_CRON] ?: true,
            watchRelay = p[K.WATCH_RELAY] ?: true,
        )
    }

    suspend fun current(): ConnectionPrefs = connection.first()
    suspend fun currentNotifications(): NotificationPrefs = notifications.first()

    suspend fun setBaseUrl(url: String) = context.dataStore.edit { it[K.BASE_URL] = url.trimEnd('/') }

    suspend fun setTokens(access: String, refresh: String?, expiresAt: Long) = context.dataStore.edit {
        it[K.ACCESS] = access
        if (refresh != null) it[K.REFRESH] = refresh
        it[K.EXPIRES] = expiresAt
    }

    suspend fun clearTokens() = context.dataStore.edit {
        it.remove(K.ACCESS); it.remove(K.REFRESH); it.remove(K.EXPIRES)
    }

    suspend fun setSetupComplete(done: Boolean) = context.dataStore.edit { it[K.SETUP] = done }
    suspend fun setActiveProfile(name: String) = context.dataStore.edit { it[K.PROFILE] = name }
    suspend fun setDisplayName(name: String) = context.dataStore.edit { it[K.NAME] = name }

    suspend fun setNotificationsEnabled(v: Boolean) = context.dataStore.edit { it[K.NOTIF] = v }
    suspend fun setNtfy(server: String, topic: String, token: String) = context.dataStore.edit {
        it[K.NTFY_SERVER] = server.trimEnd('/'); it[K.NTFY_TOPIC] = topic.trim(); it[K.NTFY_TOKEN] = token.trim()
    }
    suspend fun setWatch(board: Boolean? = null, cron: Boolean? = null, relay: Boolean? = null) = context.dataStore.edit {
        board?.let { v -> it[K.WATCH_BOARD] = v }
        cron?.let { v -> it[K.WATCH_CRON] = v }
        relay?.let { v -> it[K.WATCH_RELAY] = v }
    }

    val lastCronSeen: Flow<String> = context.dataStore.data.map { it[K.LAST_CRON_SEEN] ?: "" }
    suspend fun setLastCronSeen(v: String) = context.dataStore.edit { it[K.LAST_CRON_SEEN] = v }
    val lastBoardCursor: Flow<Long> = context.dataStore.data.map { it[K.LAST_BOARD_CURSOR] ?: 0L }
    suspend fun setLastBoardCursor(v: Long) = context.dataStore.edit { it[K.LAST_BOARD_CURSOR] = v }

    suspend fun setPkce(verifier: String, state: String) = context.dataStore.edit {
        it[K.PKCE_VERIFIER] = verifier; it[K.PKCE_STATE] = state
    }
    suspend fun takePkce(): Pair<String, String>? {
        val p = context.dataStore.data.first()
        val v = p[K.PKCE_VERIFIER] ?: return null
        val s = p[K.PKCE_STATE] ?: return null
        context.dataStore.edit { it.remove(K.PKCE_VERIFIER); it.remove(K.PKCE_STATE) }
        return v to s
    }

    /** Local display names per profile, stored as "profile=name" lines. */
    suspend fun currentNicknames(): Map<String, String> =
        (context.dataStore.data.first()[K.NICKNAMES] ?: "").lineSequence()
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
            .filterValues { it.isNotBlank() }

    suspend fun setNickname(profile: String, nickname: String) {
        val map = currentNicknames().toMutableMap()
        if (nickname.isBlank()) map.remove(profile) else map[profile] = nickname.trim().replace('\n', ' ')
        context.dataStore.edit { it[K.NICKNAMES] = map.entries.joinToString("\n") { (k, v) -> "$k=$v" } }
    }

    suspend fun resetAll() = context.dataStore.edit { it.clear() }
}
