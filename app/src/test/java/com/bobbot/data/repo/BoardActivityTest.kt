package com.bobbot.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardActivityTest {
    private fun activity(createdBy: String?, title: String) =
        BoardActivity(id = "1", kind = "completed", from = "default", to = "steve", taskId = "t", taskTitle = title, text = "", at = null, createdBy = createdBy)

    @Test fun permissionReviewsArePlumbing() {
        assertTrue(activity("bobbot-team", "Permission: steve wants to use terminal").isTeamPlumbing)
        assertTrue(activity("default", "permission: steve wants to use terminal").isTeamPlumbing)
    }

    @Test fun realHandoffsAreNot() {
        assertFalse(activity("default", "Compare desk chargers").isTeamPlumbing)
        assertFalse(activity(null, "Porch light automation").isTeamPlumbing)
    }
}
