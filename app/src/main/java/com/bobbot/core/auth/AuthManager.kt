package com.bobbot.core.auth

import android.util.Base64
import android.util.Log
import com.bobbot.core.net.HermesClient
import com.bobbot.core.net.json
import com.bobbot.core.net.jsonOf
import com.bobbot.core.net.long
import com.bobbot.core.net.str
import com.bobbot.data.prefs.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthState {
    data object Idle : AuthState
    data class WaitingForBrowser(val authorizeUrl: String) : AuthState
    data object Exchanging : AuthState
    data class SignedIn(val userId: String, val displayName: String) : AuthState
    data class Failed(val message: String) : AuthState
}

/**
 * RFC 8252 native-app login against the Hermes dashboard:
 *   1. bind a listener on 127.0.0.1:<random port>
 *   2. open GET /auth/native/authorize in the system browser with PKCE S256
 *   3. the browser is redirected back to http://127.0.0.1:<port>/cb?code=&state=
 *   4. POST /auth/native/token {code, code_verifier} -> bearer tokens
 * On Android the Custom Tab runs on the same device, so the loopback listener is reachable.
 */
@Singleton
class AuthManager @Inject constructor(
    private val client: HermesClient,
    private val tokens: TokenStore,
    private val prefs: AppPrefs,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<AuthState>(AuthState.Idle)
    val state: StateFlow<AuthState> = _state

    private var server: ServerSocket? = null
    private var listenJob: Job? = null
    private var verifier: String = ""
    private var expectedState: String = ""

    /** Starts the flow and returns the URL to open in a Custom Tab. */
    suspend fun beginLogin(provider: String = ""): String {
        cancel()
        val rnd = SecureRandom()
        verifier = b64url(ByteArray(48).also(rnd::nextBytes))
        expectedState = b64url(ByteArray(24).also(rnd::nextBytes))
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        val ss = withContext(Dispatchers.IO) { ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")) }
        server = ss
        val redirect = "http://127.0.0.1:${ss.localPort}/cb"
        val url = client.url(
            "/auth/native/authorize",
            mapOf(
                "provider" to provider,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "redirect_uri" to redirect,
                "state" to expectedState,
            ),
        ).toString()
        _state.value = AuthState.WaitingForBrowser(url)
        listenJob = scope.launch { serve(ss) }
        return url
    }

    private suspend fun serve(ss: ServerSocket) {
        try {
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (_: Exception) { break }
                handle(sock)
            }
        } finally {
            runCatching { ss.close() }
        }
    }

    private suspend fun handle(sock: Socket) {
        sock.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val requestLine = reader.readLine() ?: return
            // drain headers
            while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
            val path = requestLine.split(' ').getOrNull(1) ?: "/"
            val q = path.substringAfter('?', "").split('&').filter { it.contains('=') }
                .associate { kv -> kv.substringBefore('=') to URLDecoder.decode(kv.substringAfter('='), "UTF-8") }
            val code = q["code"]
            val state = q["state"]
            val ok = !code.isNullOrBlank() && state == expectedState
            val html = if (ok) successPage() else errorPage(q["error"] ?: "Missing or mismatched state")
            val out = s.getOutputStream()
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html").toByteArray())
            out.flush()
            if (ok) {
                _state.value = AuthState.Exchanging
                runCatching { server?.close() }
                exchange(code!!)
            } else if (!q.containsKey("favicon")) {
                _state.value = AuthState.Failed("Sign-in was rejected: ${q["error"] ?: "state mismatch"}")
            }
        }
    }

    private suspend fun exchange(code: String) {
        try {
            val body = jsonOf("code" to code, "code_verifier" to verifier).toString()
            val req = Request.Builder().url(client.url("/auth/native/token"))
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            val text = withContext(Dispatchers.IO) {
                client.okHttp.newCall(req).execute().use { r ->
                    val t = r.body?.string() ?: ""
                    if (!r.isSuccessful) throw IllegalStateException("Token exchange failed (${r.code}): ${t.take(200)}")
                    t
                }
            }
            val j = json.parseToJsonElement(text)
            val access = j.str("access_token") ?: throw IllegalStateException("No access token in response")
            tokens.save(access, j.str("refresh_token"), j.long("expires_at") ?: 0L)
            val userId = j.str("user_id") ?: ""
            // Best effort: fetch display name.
            val name = runCatching { client.get("/api/auth/me").let { it.str("display_name") ?: it.str("email") ?: "" } }.getOrDefault("")
            prefs.setDisplayName(name)
            _state.value = AuthState.SignedIn(userId, name)
        } catch (e: Exception) {
            Log.w("AuthManager", "exchange failed", e)
            _state.value = AuthState.Failed(e.message ?: "Sign-in failed")
        }
    }

    /** Loopback-bound servers: accept a manually supplied session token. */
    suspend fun useSessionToken(token: String): Boolean {
        tokens.saveSessionToken(token.trim())
        return runCatching { client.get("/api/sessions", mapOf("limit" to "1")); true }.getOrElse {
            tokens.clear(); false
        }
    }

    fun cancel() {
        listenJob?.cancel(); listenJob = null
        runCatching { server?.close() }; server = null
        if (_state.value is AuthState.WaitingForBrowser) _state.value = AuthState.Idle
    }

    fun reset() { cancel(); _state.value = AuthState.Idle }

    suspend fun signOut() {
        tokens.clear()
        _state.value = AuthState.Idle
    }

    private fun b64url(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun successPage() = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta http-equiv="refresh" content="0;url=bobbot://auth/done">
        <style>body{background:#0B0D12;color:#E8EAF0;font-family:system-ui;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center}
        a{display:inline-block;margin-top:24px;padding:14px 22px;background:#7C9CFF;color:#0B0D12;border-radius:14px;text-decoration:none;font-weight:600}</style></head>
        <body><div><h2>Signed in to Hermes</h2><p>You can return to BobBot now.</p><a href="bobbot://auth/done">Open BobBot</a></div></body></html>
    """.trimIndent()

    private fun errorPage(msg: String) = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>body{background:#0B0D12;color:#E8EAF0;font-family:system-ui;padding:32px}</style></head>
        <body><h2>Sign-in failed</h2><p>${msg.replace("<", "&lt;")}</p><p><a style="color:#7C9CFF" href="bobbot://auth/failed">Back to BobBot</a></p></body></html>
    """.trimIndent()
}
