package com.bobbot.data.repo

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamRequestTest {
    private val now = System.currentTimeMillis() / 1000.0

    private fun row(status: String, args: String = """{"command":"ls -la","timeout":30}""", expires: Double = now + 3600) = TeamRequest.from(Json.parseToJsonElement(
        """{"id":"r1","profile":"steve","tool":"terminal","args":${kotlinx.serialization.json.JsonPrimitive(args)},"task":"","status":"$status","reason":"Bounded","reviewer":"default","scope":"exact","created":$now,"expires":$expires}"""))

    @Test fun argumentsBecomeReadablePairsWithTheCommandFirst() {
        val r = row("pending")
        assertEquals("ls -la", r.headline)
        assertEquals(mapOf("command" to "ls -la", "timeout" to "30"), r.args)
        assertEquals(mapOf("arguments" to "not json"), TeamRequest.parseArgs("not json"))
    }

    @Test fun actionToolsHeadlineTheActionWithItsArguments() {
        val r = row("pending", args = """{"action":"remove","job_id":"71f665b46299"}""")
        assertEquals("remove job_id=71f665b46299", r.headline)
        assertTrue(r.details.isEmpty())
        assertEquals(mapOf("timeout" to "30"), row("pending").details)
    }

    @Test fun whoIsWaitingOnWhom() {
        assertTrue(row("needs_user").needsYou)
        assertTrue(row("pending").waitingForAuthority)
        assertFalse(row("approved").open)
        assertTrue(row("needs_user", expires = now - 10).expired)
        assertFalse(row("needs_user", expires = now - 10).needsYou)
        assertFalse(row("consumed", expires = now - 10).expired)
    }
}
