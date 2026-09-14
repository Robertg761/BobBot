package com.bobbot.ui.inbox

import com.bobbot.data.model.Bot
import com.bobbot.data.repo.BotChat
import com.bobbot.data.repo.ChatItem
import com.bobbot.data.repo.ChatSessionState
import com.bobbot.data.repo.GroupRoom
import com.bobbot.data.repo.InboxKeys

enum class InboxKind { BOT, GROUP }

/** What a bot is doing right now, shown as a dot on its avatar. */
enum class Presence { IDLE, WORKING, WAITING }

/**
 * One row of the inbox. Bots are the main objects: each has exactly one ongoing conversation
 * here, and groups sit in the same list. Rows are sorted like a messages app: pinned first,
 * then most recent activity, then bots that have never been messaged.
 */
data class InboxRow(
    val key: String,
    val kind: InboxKind,
    val profile: String = "",
    val roomId: String = "",
    val title: String,
    val preview: String,
    val lastActive: Long,
    val pinned: Boolean = false,
    val unread: Boolean = false,
    val presence: Presence = Presence.IDLE,
    val members: List<String> = emptyList(),
    val isDefault: Boolean = false,
    /** The ongoing conversation's stored id, when the server has one. */
    val sessionId: String? = null,
)

/** Server timestamps are epoch seconds, occasionally millis. */
fun epochMillis(raw: Double): Long = when {
    raw <= 0.0 -> 0L
    raw > 1_000_000_000_000.0 -> raw.toLong()
    else -> (raw * 1000.0).toLong()
}

/** Group rooms have no server read state; "opened after the last change" is the best local answer. */
fun isUnread(lastActive: Long, seenAt: Long?): Boolean = seenAt != null && lastActive > seenAt

/**
 * @param chats each bot's canonical conversation as Hermes reports it (preview, activity, unread, pin)
 * @param live sessions open in this app, for instant presence and previews while a reply streams
 * @param working profiles whose server-side worker heartbeat is fresh
 * @param seen when each group room was last opened here
 */
fun buildInbox(
    bots: List<Bot>,
    chats: Map<String, BotChat>,
    live: List<ChatSessionState>,
    working: Set<String>,
    rooms: List<GroupRoom>,
    seen: Map<String, Long>,
    nameOf: (String) -> String,
): List<InboxRow> {
    val liveByProfile = live.groupBy { it.profile }
    val botRows = bots.map { bot ->
        val chat = chats[bot.name]
        val anyLive = liveByProfile[bot.name].orEmpty()
        val liveMain = anyLive.firstOrNull { it.storedId != null && (it.storedId == chat?.resolvedId || it.storedId == chat?.id) }
        val presence = when {
            anyLive.any { it.status == "waiting" } -> Presence.WAITING
            anyLive.any { it.isBusy } || bot.name in working -> Presence.WORKING
            else -> Presence.IDLE
        }
        val name = nameOf(bot.name)
        val preview = liveMain?.let { livePreview(it) }
            ?: chat?.preview?.takeIf { it.isNotBlank() }?.let { flatten(it) }
            ?: bot.description.takeIf { it.isNotBlank() }
            ?: "Say hi to $name"
        InboxRow(
            key = InboxKeys.bot(bot.name),
            kind = InboxKind.BOT,
            profile = bot.name,
            title = name,
            preview = preview,
            lastActive = epochMillis(chat?.lastActive ?: 0.0),
            pinned = chat?.pinned == true,
            unread = chat?.unread == true && liveMain?.isBusy != true,
            presence = presence,
            isDefault = bot.isDefault,
            sessionId = chat?.resolvedId,
        )
    }
    val roomRows = rooms.map { room ->
        val lastActive = epochMillis(room.updatedAt)
        InboxRow(
            key = InboxKeys.room(room.id),
            kind = InboxKind.GROUP,
            roomId = room.id,
            title = room.name.ifBlank { room.members.joinToString(", ") { nameOf(it) } },
            preview = room.members.joinToString(", ") { nameOf(it) },
            lastActive = lastActive,
            unread = isUnread(lastActive, seen[InboxKeys.room(room.id)]),
            members = room.members,
        )
    }
    return (botRows + roomRows).sortedWith(
        compareByDescending<InboxRow> { it.pinned }
            .thenByDescending { it.lastActive }
            .thenByDescending { it.isDefault }
            .thenBy { it.title.lowercase() },
    )
}

/** The last thing that happened in an open session, phrased like a preview line. */
fun livePreview(s: ChatSessionState): String? {
    val last = s.items.lastOrNull() ?: return null
    return when (last) {
        is ChatItem.User -> "You: " + last.text.ifBlank { "(image)" }
        is ChatItem.Assistant -> when {
            last.text.isNotBlank() -> last.text
            last.streaming -> "Typing…"
            else -> null
        }
        is ChatItem.Teammate -> "${com.bobbot.data.repo.BotNames.display(last.profile)}: ${last.text}"
        is ChatItem.Tool -> "Using ${last.name}…"
        is ChatItem.Delegation -> "Working on: ${last.goal}"
        is ChatItem.System -> null
    }?.let { flatten(it) }
}

/** One line of plain text: markdown emphasis, code ticks, headings and bullets stripped. */
fun flatten(text: String): String = text
    .replace(Regex("(?m)^\\s*(#{1,6}\\s+|[-*+]\\s+|\\d+\\.\\s+|>\\s+)"), "")
    .replace(Regex("[*_`~]+"), "")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(200)
