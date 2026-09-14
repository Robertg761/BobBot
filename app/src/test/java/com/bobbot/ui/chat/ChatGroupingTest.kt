package com.bobbot.ui.chat

import com.bobbot.data.repo.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGroupingTest {
    private fun tool(id: String, name: String = "read_file", done: Boolean = true, durationS: Double? = null) =
        ChatItem.Tool(id, toolId = id, name = name, context = "notes.md", done = done, durationS = durationS)

    private fun reasoning(id: String, at: Long? = null) = ChatItem.Assistant(id, text = "", reasoning = "thinking hard", streaming = false, status = "complete", at = at)
    private fun said(id: String, text: String = "Here you go", at: Long? = null) = ChatItem.Assistant(id, text = text, streaming = false, status = "complete", at = at)

    @Test fun consecutiveStepsBecomeOneRun() {
        val items = listOf(
            ChatItem.User("u1", "do it", at = 1_000),
            reasoning("a1"), tool("t1"), reasoning("a2"), tool("t2"), ChatItem.System("s1", "compacted", "compacted"),
            said("a3"),
        )
        val entries = groupChatItems(items)
        assertEquals(3, entries.size)
        assertTrue(entries[0] is Entry.Single)
        val run = entries[1] as Entry.Run
        assertEquals(5, run.items.size)
        assertEquals("a1", run.key)
        assertEquals("a3", (entries[2] as Entry.Single).item.id)
    }

    @Test fun bubblesBreakARun() {
        val items = listOf(tool("t1"), tool("t2"), said("a1"), tool("t3"), tool("t4"))
        val entries = groupChatItems(items)
        assertEquals(3, entries.size)
        assertEquals(2, (entries[0] as Entry.Run).items.size)
        assertEquals(2, (entries[2] as Entry.Run).items.size)
    }

    @Test fun loneStepStaysItself() {
        val entries = groupChatItems(listOf(tool("t1"), said("a1")))
        assertTrue(entries[0] is Entry.Single)
        assertEquals("t1", entries[0].key)
    }

    @Test fun errorsAndWarningsStayVisible() {
        val items = listOf(tool("t1"), ChatItem.System("s1", "disk full", "error"), tool("t2"), ChatItem.System("s2", "slow", "warn"))
        val entries = groupChatItems(items)
        assertEquals(4, entries.size)
        assertTrue(entries.all { it is Entry.Single })
        assertFalse(isWorkingStep(ChatItem.System("s3", "boom", "error")))
        assertFalse(isWorkingStep(ChatItem.Assistant("a9", text = "", error = "failed")))
        assertFalse(isWorkingStep(ChatItem.Assistant("a8", text = "", status = "interrupted", streaming = false)))
    }

    @Test fun teammatesAndDelegationsAreNeverSteps() {
        assertFalse(isWorkingStep(ChatItem.Teammate("m1", "mira", "hi")))
        assertFalse(isWorkingStep(ChatItem.Delegation("d1", "sub", "goal", "running")))
        assertFalse(isWorkingStep(ChatItem.User("u1", "hi")))
        assertTrue(isWorkingStep(reasoning("a1")))
        assertTrue(isWorkingStep(tool("t1")))
    }

    @Test fun emptyTranscriptGroupsToNothing() {
        assertEquals(emptyList<Entry>(), groupChatItems(emptyList()))
    }

    @Test fun durationPrefersTimestampSpan() {
        val items = listOf(reasoning("a1", at = 10_000), tool("t1", durationS = 2.0), reasoning("a2", at = 52_000))
        assertEquals(42.0, runDurationS(items)!!, 0.001)
    }

    @Test fun durationFallsBackToToolTimes() {
        val items = listOf(tool("t1", durationS = 1.5), tool("t2", durationS = 2.5))
        assertEquals(4.0, runDurationS(items)!!, 0.001)
    }

    @Test fun durationIsUnknownWithoutTimesOrTools() {
        assertNull(runDurationS(listOf(ChatItem.System("s1", "a", "status"), ChatItem.System("s2", "b", "status"))))
        // A single timestamp is a point, not a span.
        assertNull(runDurationS(listOf(reasoning("a1", at = 10_000), ChatItem.System("s1", "a", "status"))))
    }

    @Test fun labelsReadLikeASentence() {
        assertEquals("Worked for 40s · 5 steps", workedLabel(5, 40.0, live = false))
        assertEquals("Working… · 3 steps", workedLabel(3, 40.0, live = true))
        assertEquals("Worked · 1 step", workedLabel(1, null, live = false))
        assertEquals("2m 5s", shortDuration(125.0))
        assertEquals("3m", shortDuration(180.0))
        assertEquals("1h 1m", shortDuration(3_660.0))
        assertEquals("1s", shortDuration(0.2))
    }

    @Test fun stepLabelDescribesTheLatestStep() {
        assertEquals("read_file · notes.md", stepLabel(tool("t1")))
        assertEquals("Thinking…", stepLabel(reasoning("a1")))
        assertEquals("compacting context", stepLabel(ChatItem.System("s1", "[System: compacting context]", "status")))
    }
}
