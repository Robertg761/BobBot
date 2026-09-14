package com.bobbot.core.net

import android.util.Log
import com.bobbot.core.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class HermesHttpException(val code: Int, val body: String, val reason: String? = null) : IOException("HTTP $code: ${reason ?: body.take(200)}") {
    val isAuthError: Boolean get() = code == 401
    val isHostMismatch: Boolean get() = code == 400 && body.contains("Invalid Host header")
}

class NotConfiguredException : IOException("Server URL is not configured")

/**
 * Thin HTTP layer for the Hermes dashboard. Handles base URL, bearer auth,
 * transparent refresh on 401 and JSON decoding. Everything above this is typed.
 */
@Singleton
class HermesClient @Inject constructor(
    val tokens: TokenStore,
    val okHttp: OkHttpClient,
) {
    private val refreshLock = Mutex()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun baseUrl(): String = tokens.baseUrl().ifBlank { throw NotConfiguredException() }

    suspend fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val base = baseUrl().toHttpUrlOrNull() ?: throw NotConfiguredException()
        val b = base.newBuilder().encodedPath(path)
        query.forEach { (k, v) -> if (!v.isNullOrEmpty()) b.addQueryParameter(k, v) }
        return b.build()
    }

    suspend fun get(path: String, query: Map<String, String?> = emptyMap(), auth: Boolean = true): JsonElement =
        exec(Request.Builder().url(url(path, query)).get(), auth)

    suspend fun delete(path: String, query: Map<String, String?> = emptyMap(), body: JsonObject? = null): JsonElement =
        exec(Request.Builder().url(url(path, query)).delete(body?.toString()?.toRequestBody(jsonType)), true)

    suspend fun post(path: String, body: JsonElement? = null, query: Map<String, String?> = emptyMap(), auth: Boolean = true): JsonElement =
        exec(Request.Builder().url(url(path, query)).post((body?.toString() ?: "{}").toRequestBody(jsonType)), auth)

    suspend fun put(path: String, body: JsonElement, query: Map<String, String?> = emptyMap()): JsonElement =
        exec(Request.Builder().url(url(path, query)).put(body.toString().toRequestBody(jsonType)), true)

    suspend fun patch(path: String, body: JsonElement, query: Map<String, String?> = emptyMap()): JsonElement =
        exec(Request.Builder().url(url(path, query)).patch(body.toString().toRequestBody(jsonType)), true)

    /** Raw text GET (used for non-JSON bodies). */
    suspend fun getText(path: String, query: Map<String, String?> = emptyMap()): String = withContext(Dispatchers.IO) {
        val req = authorize(Request.Builder().url(url(path, query)).get()).build()
        okHttp.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw HermesHttpException(r.code, text)
            text
        }
    }

    private suspend fun authorize(b: Request.Builder): Request.Builder {
        val t = tokens.accessToken()
        if (t.isNotBlank()) {
            if (tokens.isSessionToken()) b.header("X-Hermes-Session-Token", t) else b.header("Authorization", "Bearer $t")
        }
        return b
    }

    private suspend fun exec(builder: Request.Builder, auth: Boolean): JsonElement = withContext(Dispatchers.IO) {
        val first = if (auth) authorize(builder) else builder
        var resp = okHttp.newCall(first.build()).execute()
        if (resp.code == 401 && auth && tokens.refreshToken().isNotBlank()) {
            resp.close()
            val refreshed = refreshLock.withLock { tryRefresh() }
            if (refreshed) resp = okHttp.newCall(authorize(builder).build()).execute()
            else resp = okHttp.newCall(builder.build()).execute()
        }
        resp.use { r -> decode(r) }
    }

    private fun decode(r: Response): JsonElement {
        val text = r.body?.string() ?: ""
        if (!r.isSuccessful) {
            val reason = runCatching { json.parseToJsonElement(text).let { it.str("detail") ?: it.str("error") } }.getOrNull()
            throw HermesHttpException(r.code, text, reason)
        }
        if (text.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(text) }.getOrElse { throw IOException("Bad JSON from server: ${text.take(120)}") }
    }

    /** Exchange the refresh token for a new access token. Returns true on success. */
    suspend fun tryRefresh(): Boolean {
        val rt = tokens.refreshToken()
        if (rt.isBlank()) return false
        return try {
            val body = jsonOf("refresh_token" to rt, "provider" to "")
            val req = Request.Builder().url(url("/auth/native/refresh")).post(body.toString().toRequestBody(jsonType)).build()
            okHttp.newCall(req).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    if (r.code == 401) tokens.clear()
                    Log.w("HermesClient", "refresh failed ${r.code}")
                    return false
                }
                val j = json.parseToJsonElement(text)
                tokens.save(
                    access = j.str("access_token") ?: return false,
                    refresh = j.str("refresh_token"),
                    expiresAt = j.long("expires_at") ?: 0L,
                )
                true
            }
        } catch (e: Exception) {
            Log.w("HermesClient", "refresh error", e); false
        }
    }

    companion object {
    fun normalizeBaseUrl(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        val explicitScheme = s.startsWith("http://") || s.startsWith("https://")
        if (!explicitScheme) s = "http://$s"
        val url = s.toHttpUrlOrNull() ?: return null
        // Default port 9119 for a bare host or plain http without a port. An https address is a
        // tunnel or reverse proxy on 443, which is what the user meant.
        val assumeDashboardPort = url.port == HttpUrl.defaultPort(url.scheme) && !raw.contains(":${url.port}") && url.scheme == "http"
        val withPort = if (assumeDashboardPort) url.newBuilder().port(9119).build() else url
        return withPort.toString().trimEnd('/')
    }

        fun buildOkHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
