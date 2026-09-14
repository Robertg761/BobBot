package com.bobbot.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleHugTest {
    @Test fun blockMarkupTakesTheFullWidth() {
        assertTrue(hasBlockMarkup("| Job | When |\n|---|---|\n| a | b |"))
        assertTrue(hasBlockMarkup("Run:\n\n```bash\nls\n```"))
        assertTrue(hasBlockMarkup("- one\n- two"))
        assertTrue(hasBlockMarkup("1. first\n2. second"))
        assertTrue(hasBlockMarkup("# Heading\ntext"))
        assertTrue(hasBlockMarkup("> quoted"))
        assertTrue(hasBlockMarkup("![img](x.png)"))
    }

    @Test fun proseHugs() {
        assertFalse(hasBlockMarkup("Hello! What can I do for you?"))
        assertFalse(hasBlockMarkup("Got it. I looked into **chargers** and here's the short version."))
        assertFalse(hasBlockMarkup("Two lines\nof plain text with `inline code`."))
    }
}
