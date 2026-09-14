package com.bobbot.ui

import com.bobbot.data.repo.ChatRepository
import com.bobbot.data.repo.CompletionNotice
import com.bobbot.ui.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairTargetTest {
    @Test fun `a bare host gets the dashboard port`() {
        assertEquals("http://192.168.1.20:9119", pairTarget("192.168.1.20"))
    }

    @Test fun `an https tunnel is kept as it is`() {
        assertEquals("https://hermes.rjhome.top", pairTarget("https://hermes.rjhome.top"))
    }

    @Test fun `anything that is not http is refused`() {
        assertNull(pairTarget("ftp://hermes.rjhome.top"))
        assertNull(pairTarget("javascript://alert(1)"))
        assertNull(pairTarget("file:///etc/passwd"))
        assertNull(pairTarget(""))
        assertNull(pairTarget(null))
    }

    @Test fun `a link with a path is refused, because Hermes serves from the root`() {
        assertNull(pairTarget("https://hermes.rjhome.top/api/health"))
    }
}

class BannerTargetTest {
    private fun notice(profile: String, storedId: String?, title: String) =
        CompletionNotice(liveId = "live-1", storedId = storedId, profile = profile, title = title, preview = "done", status = "complete")

    private val mainChat = notice("steve", "s-1", ChatRepository.MAIN_CHAT_TITLE)
    private val taskChat = notice("steve", "s-2", "Book the flights")

    @Test fun `nothing open means the banner shows`() {
        assertFalse(mainChat.isOnScreen(null))
    }

    @Test fun `the bot's ongoing chat is matched by profile`() {
        assertTrue(mainChat.isOnScreen(Route.Chat(profile = "steve", mainConversation = true)))
        assertFalse(mainChat.isOnScreen(Route.Chat(profile = "clove", mainConversation = true)))
    }

    @Test fun `a task chat of the same bot is a different conversation`() {
        assertFalse(taskChat.isOnScreen(Route.Chat(profile = "steve", mainConversation = true)))
        assertTrue(taskChat.isOnScreen(Route.Chat(sessionId = "s-2", profile = "steve")))
        assertFalse(taskChat.isOnScreen(Route.Chat(sessionId = "s-9", profile = "steve")))
    }

    @Test fun `a route without a profile is the default bot`() {
        assertTrue(notice("default", "d-1", ChatRepository.MAIN_CHAT_TITLE).isOnScreen(Route.Chat(mainConversation = true)))
    }
}
