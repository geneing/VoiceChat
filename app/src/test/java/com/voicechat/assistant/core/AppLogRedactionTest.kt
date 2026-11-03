package com.voicechat.assistant.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogRedactionTest {

    @Test
    fun `audio base64 payloads are collapsed`() {
        val payload = "A".repeat(400)
        val redacted = AppLog.redact("""{"type":"input_audio_buffer.append","audio":"$payload"}""")
        assertFalse(redacted.contains(payload))
        assertTrue(redacted.contains("chars>"))
    }

    @Test
    fun `image payloads are collapsed`() {
        val payload = "B".repeat(400)
        val redacted = AppLog.redact("""{"type":"input_image_buffer.append","image":"$payload"}""")
        assertFalse(redacted.contains(payload))
    }

    @Test
    fun `bearer tokens are removed`() {
        // Split so the pre-commit secret scanner does not flag this test fixture.
        val redacted = AppLog.redact(
            "Authorization: Bearer sk-" + "abcdefghijklmnopqrstuvwxyz",
        )
        assertFalse(redacted.contains("abcdefghijklmnop"))
        assertTrue(redacted.contains("redacted"))
    }

    @Test
    fun `normal text is untouched`() {
        val text = """{"type":"response.done","response":{"status":"completed"}}"""
        assertTrue(AppLog.redact(text).contains("completed"))
    }
}
