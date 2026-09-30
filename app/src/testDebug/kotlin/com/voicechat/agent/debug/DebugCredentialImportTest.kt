package com.voicechat.agent.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the debug-only credential file parser.
 *
 * Lives in `src/testDebug` because [DebugCredentialImport] is compiled only for
 * the debug variant; keeping the test here means `testReleaseUnitTest` does not
 * need the debug-only class. No secret value is asserted anywhere.
 */
class DebugCredentialImportTest {
    @Test
    fun parsesTheNamedEntry() {
        assertEquals("value-123", DebugCredentialImport.parseEntry("OPENCODE_API_KEY=value-123\n", "OPENCODE_API_KEY"))
    }

    @Test
    fun ignoresCommentsBlankLinesAndOtherEntries() {
        val text =
            """
            # a comment
            OTHER_KEY=ignored

            OPENCODE_API_KEY=kept
            """.trimIndent()

        assertEquals("kept", DebugCredentialImport.parseEntry(text, "OPENCODE_API_KEY"))
    }

    @Test
    fun aValueMayContainEqualsSigns() {
        assertEquals("a=b=c", DebugCredentialImport.parseEntry("OPENCODE_API_KEY=a=b=c", "OPENCODE_API_KEY"))
    }

    @Test
    fun returnsNullWhenAbsentOrBlank() {
        assertNull(DebugCredentialImport.parseEntry("OTHER=1", "OPENCODE_API_KEY"))
        assertNull(DebugCredentialImport.parseEntry("OPENCODE_API_KEY=\n", "OPENCODE_API_KEY"))
        assertNull(DebugCredentialImport.parseEntry("", "OPENCODE_API_KEY"))
    }

    @Test
    fun theKnownEntryMatchesTheDocumentedName() {
        // The push script/README document this exact name; keep them in sync.
        assertEquals("OPENCODE_API_KEY", DebugCredentialImport.OPENCODE_API_KEY_ENTRY)
        assertEquals("debug-credentials", DebugCredentialImport.DIRECTORY_NAME)
        assertEquals("opencode-go.key", DebugCredentialImport.FILE_NAME)
    }
}
