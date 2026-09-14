package com.bobbot.data.repo

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MainConversationResolverTest {
    private class Fixture {
        val resolver = MainConversationResolver()
        var saved: String? = null
        var cached: String? = null
        var descendant: String? = "compacted"
        var failure = false
        var creates = 0
        suspend fun open() = resolver.open(
            saved = { saved }, live = { cached },
            latest = { if (failure) throw IOException("offline") else descendant },
            resume = { "resumed:$it" },
            create = { delay(1); creates++; "created" to "stored" }, save = { saved = it },
        )
    }
    @Test fun resumesCompactedConversation() = runTest {
        val f = Fixture(); f.saved = "old"
        assertEquals("resumed:compacted", f.open())
        assertEquals("compacted", f.saved)
        assertEquals(0, f.creates)
    }
    @Test fun reusesAnUnsentLiveDraft() = runTest {
        val f = Fixture(); f.saved = "draft"; f.cached = "live-draft"; f.failure = true
        assertEquals("live-draft", f.open())
    }
    @Test fun connectionFailureDoesNotReplaceConversation() = runTest {
        val f = Fixture(); f.saved = "old"; f.failure = true
        try { f.open(); fail("Expected IOException") } catch (_: IOException) { }
        assertEquals(0, f.creates); assertEquals("old", f.saved)
    }
    @Test fun deletedConversationCanBeReplaced() = runTest {
        val f = Fixture(); f.saved = "deleted"; f.descendant = null
        assertEquals("created", f.open()); assertEquals("stored", f.saved)
    }
    @Test fun concurrentFirstOpenCreatesOnce() = runTest {
        val f = Fixture()
        val first = async { f.open() }; val second = async { f.open() }
        first.await(); second.await()
        assertEquals(1, f.creates)
    }
}
