package com.voicechat.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IssueReportCommandTest {
    @Test
    fun `final speech can supply details after ASR punctuation`() {
        assertEquals(
            "Audio stopped. Switching to cellular brought it back.",
            IssueReportCommand.description(
                "  Save an issue report. Audio stopped. Switching to cellular brought it back.",
            ),
        )
        assertEquals("Audio stutters", IssueReportCommand.description("CREATE AN ISSUE REPORT: Audio stutters"))
    }

    @Test
    fun `a command without details still captures diagnostics`() {
        assertEquals("", IssueReportCommand.description("Save an issue report."))
    }

    @Test
    fun `ordinary speech and partial command words do not save reports`() {
        assertNull(IssueReportCommand.description("How do I save an issue report?"))
        assertNull(IssueReportCommand.description("Save an issue reporter"))
    }
}
