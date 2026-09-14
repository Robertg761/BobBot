package com.bobbot.ui.inbox

import com.bobbot.data.model.Bot
import com.bobbot.data.repo.BotChat
import com.bobbot.data.repo.ChatItem
import com.bobbot.data.repo.ChatSessionState
import com.bobbot.data.repo.GroupRoom
import com.bobbot.data.repo.InboxKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxModelTest {
    private fun bot(name: String, default: Boolean = false, description: String = "") =
        Bot(name = name, isDefault = default, model = "m", provider = "p", description = description, skillCount = 0, gatewayRunning = false, distributionName = null)

    private fun chat(id: String, lastActive: Double, preview: String = "hi", pinned: Boolean = false, unread: Boolean = false) =
        BotChat(id = id, resolvedId = id, preview = preview, lastActive = lastActive, messageCount = 2, unread = unread, pinned = pinned)

    private val names: (String) -> String = { it.replaceFirstChar(Char::titlecase) }

    private fun inbox(
        bots: List<Bot>, chats: Map<String, BotChat> = emptyMap(), live: List<ChatSessionState> = emptyList(),
        working: Set<String> = emptySet(), rooms: List<GroupRoom> = emptyList(), seen: Map<String, Long> = emptyMap(),
    ) = buildInbox(bots, chats, live, working, rooms, seen, names)

    @Test fun pinnedThenRecentThenNeverMessaged() {
        val rows = inbox(
            bots = listOf(bot("default", default = true), bot("steve"), bot("zed"), bot("amy")),
            chats = mapOf("steve" to chat("s1", 1_000.0), "zed" to chat("z1", 2_000.0), "amy" to chat("a1", 500.0, pinned = true)),
        )
        assertEquals(listOf("amy", "zed", "steve", "default"), rows.map { it.profile })
    }

    @Test fun groupsSortIntoTheSameListByActivity() {
        val rows = inbox(
            bots = listOf(bot("steve")), chats = mapOf("steve" to chat("s1", 1_000.0)),
            rooms = listOf(GroupRoom(id = "r1", name = "", members = listOf("steve", "clove"), updatedAt = 1_500.0, latestSeq = 3, disbanded = false)),
        )
        assertEquals(listOf(InboxKind.GROUP, InboxKind.BOT), rows.map { it.kind })
        assertEquals("Steve, Clove", rows[0].title)
        assertEquals(listOf("steve", "clove"), rows[0].members)
    }

    @Test fun botUnreadComesFromTheServerUnlessAReplyIsStreamingHere() {
        val chats = mapOf("steve" to chat("s1", 10_000.0, unread = true))
        assertTrue(inbox(listOf(bot("steve")), chats).single().unread)
        assertFalse(inbox(listOf(bot("steve")), mapOf("steve" to chat("s1", 10_000.0))).single().unread)
        val streaming = ChatSessionState(liveId = "l", storedId = "s1", profile = "steve", status = "streaming", items = listOf(ChatItem.Assistant("a", "partial")))
        assertFalse("a reply you are watching is not unread", inbox(listOf(bot("steve")), chats, live = listOf(streaming)).single().unread)
    }

    @Test fun groupUnreadIsLocalOpenedAfterLastChange() {
        val room = GroupRoom(id = "r1", name = "Ops", members = listOf("a", "b"), updatedAt = 10_000.0, latestSeq = 1, disbanded = false)
        val row = { seenAt: Long? -> inbox(emptyList(), rooms = listOf(room), seen = seenAt?.let { mapOf(InboxKeys.room("r1") to it) } ?: emptyMap()).single() }
        assertFalse("never opened is not unread", row(null).unread)
        assertTrue(row(1_000_000L).unread)
        assertFalse(row(20_000_000L).unread)
    }

    @Test fun presenceComesFromLiveSessionsOrServerWorkers() {
        val working = ChatSessionState(liveId = "l1", storedId = "task", profile = "steve", status = "working", items = listOf(ChatItem.Tool("t", "t", "read_file", "notes.md")))
        val waiting = ChatSessionState(liveId = "l2", storedId = "s1", profile = "steve", status = "waiting")
        assertEquals(Presence.WORKING, inbox(listOf(bot("steve")), live = listOf(working)).single().presence)
        assertEquals(Presence.WAITING, inbox(listOf(bot("steve")), live = listOf(working, waiting)).single().presence)
        assertEquals(Presence.WORKING, inbox(listOf(bot("steve")), working = setOf("steve")).single().presence)
        assertEquals(Presence.IDLE, inbox(listOf(bot("steve"))).single().presence)
    }

    @Test fun aDecisionForYouShowsAsWaitingWithItsOwnPreview() {
        val row = buildInbox(listOf(bot("steve")), mapOf("steve" to chat("s1", 1.0, preview = "working")), emptyList(), emptySet(), emptyList(), emptyMap(), names, needsYou = setOf("steve")).single()
        assertEquals(Presence.WAITING, row.presence)
        assertEquals("Needs your decision", row.preview)
    }

    @Test fun previewPrefersTheOpenConversationThenServerThenDescription() {
        val live = ChatSessionState(
            liveId = "l1", storedId = "s1", profile = "steve", status = "idle",
            items = listOf(ChatItem.User("u", "hello"), ChatItem.Assistant("a", "Hi  there\nRobert", streaming = false, status = "complete")),
        )
        val server = mapOf("steve" to chat("s1", 1.0, preview = "server **preview**"))
        assertEquals("Hi there Robert", inbox(listOf(bot("steve")), server, live = listOf(live)).single().preview)
        assertEquals("server preview", inbox(listOf(bot("steve")), server).single().preview)
        assertEquals("Runs the house", inbox(listOf(bot("steve", description = "Runs the house"))).single().preview)
        assertEquals("Say hi to Amy", inbox(listOf(bot("amy"))).single().preview)
    }

    @Test fun previewStripsMarkdown() {
        val s = ChatSessionState(liveId = "l", profile = "steve", items = listOf(ChatItem.Assistant("a", "Got it. I looked into **chargers**:\n\n- It's `doable`.\n1. Start cheap.", streaming = false, status = "complete")))
        assertEquals("Got it. I looked into chargers: It's doable. Start cheap.", livePreview(s))
    }

    @Test fun typingAndYourOwnMessagesReadLikeAMessagesApp() {
        val typing = ChatSessionState(liveId = "l", profile = "steve", status = "streaming", items = listOf(ChatItem.Assistant("a", "")))
        assertEquals("Typing…", livePreview(typing))
        val yours = ChatSessionState(liveId = "l", profile = "steve", items = listOf(ChatItem.User("u", "ping")))
        assertEquals("You: ping", livePreview(yours))
    }

    @Test fun epochSecondsAndMillisBothNormalise() {
        assertEquals(1_700_000_000_000L, epochMillis(1_700_000_000.0))
        assertEquals(1_700_000_000_000L, epochMillis(1_700_000_000_000.0))
        assertEquals(0L, epochMillis(0.0))
    }
}
