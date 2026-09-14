package com.bobbot.core.auth

import com.bobbot.data.prefs.AppPrefs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Access to persisted credentials. Two modes:
 *  - gated (auth_required=true): bearer access/refresh tokens from the native PKCE flow.
 *  - loopback (auth_required=false): a static session token sent as X-Hermes-Session-Token.
 */
@Singleton
class TokenStore @Inject constructor(private val prefs: AppPrefs) {
    @Volatile private var sessionTokenMode: Boolean = false

    suspend fun baseUrl(): String = prefs.current().baseUrl
    suspend fun accessToken(): String = prefs.current().accessToken
    suspend fun refreshToken(): String = prefs.current().refreshToken
    fun isSessionToken(): Boolean = sessionTokenMode

    suspend fun save(access: String, refresh: String?, expiresAt: Long) {
        sessionTokenMode = false
        prefs.setTokens(access, refresh, expiresAt)
    }

    suspend fun saveSessionToken(token: String) {
        sessionTokenMode = true
        prefs.setTokens(token, "", 0L)
    }

    suspend fun clear() = prefs.clearTokens()

    suspend fun restoreMode() {
        val c = prefs.current()
        sessionTokenMode = c.hasToken && c.refreshToken.isBlank()
    }
}
