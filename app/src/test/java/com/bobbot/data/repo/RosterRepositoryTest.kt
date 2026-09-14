package com.bobbot.data.repo

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterRepositoryTest {
    private val base = BotChat(id = "root", resolvedId = "tip", preview = "p", lastActive = 100.0, messageCount = 3)

    @Test fun unreadFollowsHermesWatermark() {
        val row = { lastRead: String, active: String -> Json.parseToJsonElement("""{"last_read_at": $lastRead, "last_activity_at": $active, "pinned": 0}""") }
        assertFalse("never tracked means read", RosterRepository.withDetail(base, row("null", "200")).unread)
        assertTrue(RosterRepository.withDetail(base, row("150", "200")).unread)
        assertFalse(RosterRepository.withDetail(base, row("250", "200")).unread)
        assertTrue("0 is the explicit unread mark", RosterRepository.withDetail(base, row("0", "200")).unread)
        assertEquals(200.0, RosterRepository.withDetail(base, row("150", "200")).lastActive, 0.0)
        assertFalse("SQLite stores pinned as 0/1", RosterRepository.withDetail(base, row("150", "200")).pinned)
    }

    @Test fun serverUnreadFlagAndPinWinWhenPresent() {
        val d = Json.parseToJsonElement("""{"unread": true, "pinned": 1, "last_read_at": 900, "last_activity_at": 200}""")
        val c = RosterRepository.withDetail(base, d)
        assertTrue(c.unread); assertTrue(c.pinned)
    }

    @Test fun workerLivenessWindow() {
        val now = 1_700_000_000_000L
        assertTrue(RosterEntry("a", null, workerLastActive = (now - 30_000) / 1000.0).workerBusy(now))
        assertFalse(RosterEntry("a", null, workerLastActive = (now - 600_000) / 1000.0).workerBusy(now))
        assertFalse(RosterEntry("a", null, workerLastActive = null).workerBusy(now))
    }
}
