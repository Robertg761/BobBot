package com.bobbot.core.net

import android.util.Log
import com.bobbot.core.auth.TokenStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

class RpcException(val code: Int, message: String) : IOException("RPC $code: $message")

/** One server-pushed event from /api/ws. `sessionId` may be empty (unscoped). */
data class GatewayEvent(val type: String, val sessionId: String, val payload: JsonElement?)

enum class SocketState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * JSON-RPC 2.0 client over the dashboard's /api/ws WebSocket.
 * - Mints a single-use ticket per connection (gated mode) or uses ?token= (loopback mode).
 * - Multiplexes request/response by id; emits all `method: "event"` frames on [events].
 * - Reconnects with backoff; callers re-resume sessions on [state] transitions.
 */
@Singleton
class GatewaySocket @Inject constructor(
    private val client: HermesClient,
    private val tokens: TokenStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectLock = Mutex()
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var manuallyClosed = false
    @Volatile private var generation = 0
    /** The single reconnect loop; onClosed can fire twice per failure (onFailure and onClosed). */
    @Volatile private var reconnectJob: kotlinx.coroutines.Job? = null

    private val _state = MutableStateFlow(SocketState.DISCONNECTED)
    val state: StateFlow<SocketState> = _state

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<GatewayEvent> = _events

    /** Emitted with the generation number every time a fresh socket is ready. */
    private val _connected = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val connected: SharedFlow<Int> = _connected

    val ready = CompletableDeferredHolder()

    suspend fun ensureConnected() {
        if (_state.value == SocketState.CONNECTED) return
        connectLock.withLock {
            if (_state.value == SocketState.CONNECTED) return
            manuallyClosed = false
            openSocket()
        }
    }

    private suspend fun openSocket() {
        _state.value = SocketState.CONNECTING
        val gen = ++generation
        val url = buildUrl()
        val req = Request.Builder().url(url).build()  // no Origin header: server allows absent Origin
        val readyDeferred = CompletableDeferred<Unit>()
        ready.set(readyDeferred)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "socket open")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                for (line in text.split('\n')) {
                    if (line.isBlank()) continue
                    handleFrame(line, readyDeferred)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failure code=${response?.code}: ${t.message}")
                if (!readyDeferred.isCompleted) readyDeferred.completeExceptionally(t)
                onClosed(gen, response?.code ?: -1, t.message ?: "failure")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { onClosed(gen, code, reason) }
        }
        ws = client.okHttp.newWebSocket(req, listener)
        try {
            withTimeout(20_000) { readyDeferred.await() }
            _state.value = SocketState.CONNECTED
            _connected.tryEmit(gen)
        } catch (e: Exception) {
            _state.value = SocketState.DISCONNECTED
            runCatching { ws?.cancel() }
            throw IOException("Could not connect to Hermes: ${e.message}", e)
        }
    }

    private suspend fun buildUrl(): String {
        val base = client.baseUrl()
        val scheme = if (base.startsWith("https")) "wss" else "ws"
        val host = base.substringAfter("://")
        val health = runCatching { client.get("/api/health", auth = false) }.getOrNull()
        val authRequired = health.bool("auth_required") ?: true
        val cred = if (authRequired && !tokens.isSessionToken()) {
            val t = client.post("/api/auth/ws-ticket").str("ticket") ?: throw IOException("Could not mint a WebSocket ticket")
            "ticket=$t"
        } else {
            "token=" + java.net.URLEncoder.encode(tokens.accessToken(), "UTF-8")
        }
        return "$scheme://$host/api/ws?$cred"
    }

    private fun handleFrame(line: String, readyDeferred: CompletableDeferred<Unit>) {
        val frame = runCatching { json.parseToJsonElement(line) }.getOrNull() ?: return
        val id = frame.child("id")?.let { (it as? JsonPrimitive)?.content }
        if (id != null && (frame.obj?.containsKey("result") == true || frame.obj?.containsKey("error") == true)) {
            val d = pending.remove(id) ?: return
            val err = frame.child("error")
            if (err != null) d.completeExceptionally(RpcException(err.int("code") ?: -1, err.str("message") ?: "error"))
            else d.complete(frame.child("result") ?: JsonObject(emptyMap()))
            return
        }
        if (frame.str("method") == "event") {
            val p = frame.child("params")
            val type = p.str("type") ?: return
            val ev = GatewayEvent(type, p.str("session_id") ?: "", p.child("payload"))
            if (type == "gateway.ready" && !readyDeferred.isCompleted) readyDeferred.complete(Unit)
            _events.tryEmit(ev)
        }
    }

    private fun onClosed(gen: Int, code: Int, reason: String) {
        if (gen != generation) return
        generation++  // consume this generation so a second callback for the same socket is ignored
        _state.value = SocketState.DISCONNECTED
        val err = IOException("Connection closed ($code): $reason")
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
        if (!manuallyClosed && reconnectJob?.isActive != true) reconnectJob = scope.launch { reconnectLoop() }
    }

    private suspend fun reconnectLoop() {
        var backoff = 1500L
        while (!manuallyClosed && _state.value != SocketState.CONNECTED) {
            delay(backoff)
            try {
                connectLock.withLock { if (_state.value != SocketState.CONNECTED && !manuallyClosed) openSocket() }
            } catch (e: Exception) {
                Log.w(TAG, "reconnect failed: ${e.message}")
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun close() {
        manuallyClosed = true
        generation++
        runCatching { ws?.close(1000, "bye") }
        ws = null
        _state.value = SocketState.DISCONNECTED
        pending.values.forEach { it.completeExceptionally(IOException("closed")) }
        pending.clear()
    }

    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 120_000): JsonElement {
        ensureConnected()
        val id = "b${ids.getAndIncrement()}"
        val d = CompletableDeferred<JsonElement>()
        pending[id] = d
        val frame = jsonOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params).toString()
        val sent = ws?.send(frame) ?: false
        if (!sent) { pending.remove(id); throw IOException("Socket not writable") }
        return try { withTimeout(timeoutMs) { d.await() } } finally { pending.remove(id) }
    }

    class CompletableDeferredHolder {
        @Volatile private var d: CompletableDeferred<Unit>? = null
        fun set(x: CompletableDeferred<Unit>) { d = x }
    }

    companion object { private const val TAG = "GatewaySocket" }
}
