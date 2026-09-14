package com.bobbot.data.repo

import org.junit.Assert.*
import org.junit.Test

class BotIdentityTest {
    @Test fun templateLabelsCannotOverrideTheProfileName() {
        for (heading in listOf("Persona", "SOUL.md", "Identity", "Voice", "Rules")) {
            assertNull(BotNames.headingOf("# $heading\n\nInstructions"))
        }
        assertNull(BotNames.headingOf("Instructions\n\n## Voice\nBe brief."))
        assertEquals("Clove", BotNames.headingOf("# Clove\n\nAuthority bot."))
    }

    @Test fun templateGetsChosenNameAndKeepsInstructions() {
        val body = "You run the house.\n\n## Voice\nBe brief."
        val persona = newBotPersona("steve", "", "# Persona\n\n$body")
        assertEquals("Steve", BotNames.headingOf(persona))
        assertTrue(persona.contains("You are Steve."))
        assertTrue(persona.endsWith(body))
    }

    @Test fun customInstructionsSurviveAndBlankPersonasGetAnIdentity() {
        val custom = "# Household helper\n\nNever change a schedule without asking."
        assertTrue(newBotPersona("steve", "", custom).endsWith(custom))
        assertEquals("Steve", BotNames.headingOf(newBotPersona("steve", "Household assistant", null)))
    }

    @Test fun explicitNicknameStillWinsOverPersona() {
        BotNames.setPersona("identity-test", "Steve")
        BotNames.setNicknames(mapOf("identity-test" to "Steven"))
        try { assertEquals("Steven", BotNames.display("identity-test")) }
        finally {
            BotNames.setNicknames(emptyMap())
            BotNames.setPersona("identity-test", null)
        }
        assertEquals("Steve", BotNames.fallback("steve"))
    }
}
