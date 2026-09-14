package com.bobbot.data.repo

import com.bobbot.core.net.GatewaySocket
import com.bobbot.core.net.RpcException
import com.bobbot.core.net.bool
import com.bobbot.core.net.child
import com.bobbot.core.net.dbl
import com.bobbot.core.net.int
import com.bobbot.core.net.jsonOf
import com.bobbot.core.net.list
import com.bobbot.core.net.long
import com.bobbot.core.net.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** A Hermes-hosted group conversation between two to six bots. */
data class GroupRoom(
    val id: String,
    val name: String,
    val members: List<String>,
    val updatedAt: Double,
    val latestSeq: Long,
    val disbanded: Boolean,
) {
    companion object {
        fun from(j: JsonElement): GroupRoom = GroupRoom(
            id = j.str("room_id") ?: "",
            name = j.str("name") ?: "",
            members = j.list("members").mapNotNull { it.str("profile") ?: it.str("member_id") }.distinct(),
            updatedAt = j.dbl("updated_at") ?: j.dbl("created_at") ?: 0.0,
            latestSeq = j.long("latest_seq") ?: 0L,
            disbanded = j.child("disbanded_at") != null,
        )
    }
}

/** One entry of a room's log, reduced to what the transcript shows. */
data class GroupEvent(
    val seq: Long,
    val kind: String,
    val actorKind: String,
    val profile: String?,
    val displayName: String?,
    val text: String,
    val createdAt: Double,
) {
    val fromUser: Boolean get() = actorKind == "user"
    val isMessage: Boolean get() = kind == "message.user" || kind == "message.member"

    companion object {
        fun from(j: JsonElement): GroupEvent {
            val actor = j.child("actor")
            val payload = j.child("payload")
            return GroupEvent(
                seq = j.long("seq") ?: 0L,
                kind = j.str("kind") ?: "",
                actorKind = actor.str("kind") ?: "",
                profile = actor.str("profile"),
                displayName = actor.str("display_name"),
                text = payload.str("text") ?: payload.str("message") ?: "",
                createdAt = j.dbl("created_at") ?: 0.0,
            )
        }
    }
}

@Singleton
class GroupsRepository @Inject constructor(private val socket: GatewaySocket) {

    private suspend fun rpc(method: String, params: JsonObject = jsonOf()): JsonElement {
        socket.ensureConnected()
        return socket.call(method, params)
    }

    /** False when the server has no group worker (older Hermes) or the method is missing. */
    suspend fun supported(): Boolean = try {
        rpc("groups.capabilities").bool("driver") == true
    } catch (e: RpcException) {
        if (e.code == -32601) false else throw e
    }

    suspend fun rooms(): List<GroupRoom> = rpc("groups.list").list("rooms").map(GroupRoom::from).filterNot { it.disbanded }.filter { it.id.isNotBlank() }.distinctBy { it.id }

    suspend fun create(roomId: String, profiles: List<String>, name: String) {
        require(profiles.size in 2..6) { "Choose between two and six bots." }
        rpc(
            "groups.create",
            jsonOf(
                "room_id" to roomId,
                "name" to name.ifBlank { profiles.joinToString(" + ") { BotNames.display(it) } },
                "members" to profiles.map { jsonOf("member_id" to it, "profile" to it, "handle" to it, "display_name" to BotNames.display(it)) },
            ),
        )
    }

    /** Returns the page's events and the cursor for the next call. */
    suspend fun log(roomId: String, sinceSeq: Long, limit: Int = 100): Triple<List<GroupEvent>, Long, Boolean> {
        val page = rpc("groups.log", jsonOf("room_id" to roomId, "since_seq" to sinceSeq, "limit" to limit))
        val events = page.list("events").map(GroupEvent::from)
        val next = page.long("cursor") ?: sinceSeq
        return Triple(events, next, page.bool("has_more") == true && next > sinceSeq)
    }

    data class RoomStatus(val working: Boolean, val pendingActions: List<JsonElement>)

    suspend fun status(roomId: String): RoomStatus {
        val status = rpc("groups.state", jsonOf("room_id" to roomId)).child("driver_status")
        return RoomStatus(working = status.bool("working") == true, pendingActions = status.list("pending_actions"))
    }

    suspend fun send(roomId: String, eventId: String, text: String) {
        rpc("groups.send", jsonOf("room_id" to roomId, "event_id" to eventId, "payload" to jsonOf("text" to text, "thread_id" to "main")))
    }

    suspend fun stop(roomId: String, cancelId: String) {
        rpc("groups.stop", jsonOf("room_id" to roomId, "cancel_id" to cancelId))
    }

    suspend fun respond(roomId: String, action: JsonElement, choice: String) {
        rpc(
            if (action.str("kind") == "retry") "groups.retry" else "groups.approve",
            jsonOf(
                "room_id" to roomId, "task_id" to action.str("task_id"), "member_id" to action.str("member_id"),
                "execution_generation" to action.int("execution_generation"), "request_id" to action.str("request_id"), "choice" to choice,
            ),
        )
    }
}
