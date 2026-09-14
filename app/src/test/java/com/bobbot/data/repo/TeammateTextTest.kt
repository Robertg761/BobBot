package com.bobbot.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeammateTextTest {
    @Test fun dmFromTeammateIsAttributed() {
        val p = TeammateText.parse("Message from 🤖 steve (@steve): Can you approve the porch automation?\nThanks.")
        assertEquals(TeammateText.Parsed("steve", "Can you approve the porch automation?\nThanks.", reply = false), p)
        assertEquals("default", TeammateText.parse("Message from 🤖 hermes (@hermes): hi")!!.profile)
    }

    @Test fun replyCompletionIsAttributedToTheTargetProfile() {
        val text = "[IMPORTANT: Background process 7 completed normally (exit code 0).\n" +
            "Command: hermes -p steve chat --in ~ -c \"Bot Chat\" --create-if-missing -Q --query-file /tmp/dm-1.txt\n" +
            "Output:\nThree options fit under \$400.\nThe Anker is the one I'd get.]"
        val p = TeammateText.parse(text)
        assertEquals(TeammateText.Parsed("steve", "Three options fit under \$400.\nThe Anker is the one I'd get.", reply = true), p)
    }

    @Test fun otherBackgroundProcessesAreNotTeammates() {
        val text = "[IMPORTANT: Background process 3 completed normally (exit code 0).\nCommand: npm test\nOutput:\nall good]"
        assertNull(TeammateText.parse(text))
        assertEquals("Background process finished: npm test", TeammateText.processTitle(text))
        assertNull(TeammateText.parse("Message from my friend: hello"))
        assertNull(TeammateText.parse("just a normal message"))
    }
}
