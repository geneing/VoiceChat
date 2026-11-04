package com.voicechat.assistant.data.prefs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsDefaultsTest {

    @Test
    fun `server side search is the default and keeps the client tools`() {
        val settings = AppSettings()
        assertTrue(settings.nativeSearch)
        assertTrue(settings.toolsEnabled)
        assertTrue(settings.searchSources)
    }
}
