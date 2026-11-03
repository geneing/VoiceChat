package com.voicechat.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `bytes use B KB and MB`() {
        assertEquals("0 B", Formatters.bytes(0))
        assertEquals("512 B", Formatters.bytes(512))
        assertEquals("1.5 KB", Formatters.bytes(1_536))
        assertEquals("2.00 MB", Formatters.bytes(2_097_152))
    }
}
