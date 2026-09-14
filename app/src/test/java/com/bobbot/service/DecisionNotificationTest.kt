package com.bobbot.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shade's decision actions: stable ids, distinct request codes, and the wording after a tap. */
class DecisionNotificationTest {

    @Test
    fun `notification id is stable per request`() {
        assertEquals(Notifier.decisionNotificationId("req-1"), Notifier.decisionNotificationId("req-1"))
        assertNotEquals(Notifier.decisionNotificationId("req-1"), Notifier.decisionNotificationId("req-2"))
        assertTrue(Notifier.decisionNotificationId("req-1") >= 0)
    }

    @Test
    fun `each action gets its own request code`() {
        val codes = (0..2).map { Notifier.requestCode("req-1", it) }
        assertEquals(3, codes.toSet().size)
        codes.forEach { assertTrue(it >= 0) }
        assertNotEquals(Notifier.requestCode("req-1", 0), Notifier.requestCode("req-2", 0))
    }

    @Test
    fun `summary names the bot, the scope and the tool`() {
        assertEquals("Allowed for Steve", Notifier.decisionSummary("Steve", "terminal", "approved", "exact"))
        assertEquals("Allowed terminal for Steve here", Notifier.decisionSummary("Steve", "terminal", "approved", "tool"))
        assertEquals("Denied for Steve", Notifier.decisionSummary("Steve", "terminal", "denied", "exact"))
        assertEquals("Denied for Steve", Notifier.decisionSummary("Steve", "terminal", "denied", "tool"))
        assertEquals("Allowed that tool for Steve here", Notifier.decisionSummary("Steve", "", "approved", "tool"))
    }

    @Test
    fun `shortcut ids are namespaced per profile`() {
        assertEquals("bot:steve", Shortcuts.idFor("steve"))
        assertNotEquals(Shortcuts.idFor("steve"), Shortcuts.idFor("default"))
    }
}
