package com.bobbot.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A message in a bot-to-bot relay conversation, always attributed to a named bot. */
data class RelayMessage(val id: String, val from: String, val to: String, val text: String, val at: Long = System.currentTimeMillis(), val kind: String = "bot")

data class RelayState(
    val id: String = "",
    val botA: String = "",
    val botB: String = "",
    val topic: String = "",
    val maxRounds: Int = 6,
    val round: Int = 0,
    val running: Boolean = false,
    val speaking: String? = null,
    val messages: List<RelayMessage> = emptyList(),
    val error: String? = null,
    val liveA: String? = null,
    val liveB: String? = null,
)

/**
 * Client-side bot-to-bot conversations: BobBot opens a session with each bot and passes replies
 * back and forth. Every message carries an explicit "from → to" so it is always clear who is talking.
 * Hermes itself has no agent-to-agent primitive beyond the kanban board, so this is how two bots
 * can actually talk to each other from the phone.
 */
@Singleton
class RelayRepository @Inject constructor(private val chat: ChatRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(RelayState())
    val state: StateFlow<RelayState> = _state
    private var job: Job? = null

    fun start(botA: String, botB: String, topic: String, rounds: Int) {
        stop()
        _state.value = RelayState(id = UUID.randomUUID().toString(), botA = botA, botB = botB, topic = topic, maxRounds = rounds, running = true)
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel(); job = null
        val s = _state.value
        _state.update { it.copy(running = false, speaking = null) }
        scope.launch {
            s.liveA?.let { runCatching { chat.interrupt(it) } }
            s.liveB?.let { runCatching { chat.interrupt(it) } }
        }
    }

    /** The user can interject; the next bot to speak sees it. */
    fun interject(text: String) {
        _state.update { it.copy(messages = it.messages + RelayMessage(UUID.randomUUID().toString(), "you", "both", text, kind = "user")) }
    }

    private suspend fun run() {
        val s0 = _state.value
        try {
            val liveA = chat.createSession(s0.botA, closeOnDisconnect = true)
            val liveB = chat.createSession(s0.botB, closeOnDisconnect = true)
            _state.update { it.copy(liveA = liveA, liveB = liveB) }
            val intro = "You are ${s0.botA}. You are in a conversation with another assistant named ${s0.botB}, relayed by BobBot. " +
                "Robert is watching and may interject. Keep replies concise (under 120 words) and speak directly to ${s0.botB}. Topic: ${s0.topic}"
            var speaker = s0.botA; var listener = s0.botB
            var liveSpeaker = liveA; var liveListener = liveB
            var incoming = intro
            var first = true
            for (round in 1..s0.maxRounds * 2) {
                _state.update { it.copy(round = (round + 1) / 2, speaking = speaker) }
                val interjections = _state.value.messages.filter { it.kind == "user" && it.at > lastSeen }
                lastSeen = System.currentTimeMillis()
                val prompt = buildString {
                    if (!first) append("[Message from $listener via BobBot]\n")
                    append(incoming)
                    if (interjections.isNotEmpty()) append("\n\n[Robert interjects]\n" + interjections.joinToString("\n") { it.text })
                }
                chat.send(liveSpeaker, prompt, fromBot = if (first) null else listener)
                val reply = awaitReply(liveSpeaker) ?: throw IllegalStateException("$speaker did not reply")
                _state.update { it.copy(messages = it.messages + RelayMessage(UUID.randomUUID().toString(), speaker, listener, reply)) }
                if (first) {
                    // Give B its own intro before the first relayed message.
                    incoming = "You are $listener. You are in a conversation with another assistant named $speaker, relayed by BobBot. " +
                        "Robert is watching and may interject. Keep replies concise (under 120 words) and speak directly to $speaker. Topic: ${s0.topic}\n\n[Message from $speaker via BobBot]\n$reply"
                    first = false
                } else incoming = reply
                // swap
                val ts = speaker; speaker = listener; listener = ts
                val tl = liveSpeaker; liveSpeaker = liveListener; liveListener = tl
            }
            _state.update { it.copy(running = false, speaking = null) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(running = false, speaking = null, error = e.message ?: "Relay failed") }
        }
    }

    private var lastSeen = 0L

    private suspend fun awaitReply(liveId: String): String? {
        val flow = chat.state(liveId) ?: return null
        return withTimeoutOrNull(600_000) {
            // wait until the last assistant bubble is complete and the session is idle
            val st = flow.first { s ->
                val last = s.items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant
                (s.status == "idle" && last != null && !last.streaming && last.text.isNotBlank()) || s.error != null
            }
            if (st.error != null && st.items.lastOrNull { it is ChatItem.Assistant } == null) throw IllegalStateException(st.error)
            (st.items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.text
        }
    }
}
