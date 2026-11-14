package com.voicechat.assistant.data.prefs

import com.voicechat.assistant.core.PromptCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsDefaultsTest {

    /** The shipped asset, found from either the module or the repository root. */
    private fun promptsFile(): File {
        val candidates = listOf(
            File("src/main/assets/${PromptCatalog.ASSET_NAME}"),
            File("app/src/main/assets/${PromptCatalog.ASSET_NAME}"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("assets/${PromptCatalog.ASSET_NAME} not found from ${File(".").absolutePath}")
    }

    private fun catalog(): PromptCatalog.Catalog {
        val parsed = PromptCatalog.parse(promptsFile().readText())
        assertNotNull("prompts.json must parse", parsed)
        return parsed!!
    }

    @Test
    fun `server side search is the default and keeps the client tools`() {
        val settings = AppSettings()
        assertTrue(settings.nativeSearch)
        assertTrue(settings.toolsEnabled)
        assertTrue(settings.searchSources)
    }

    @Test
    fun `the default prompt asks for complete factual technical answers`() {
        val prompt = catalog().defaultPrompt
        assertTrue(prompt.contains("concise but comprehensive"))
        assertTrue(prompt.contains("technical"))
        assertTrue(prompt.contains("facts", ignoreCase = true))
        assertTrue(prompt.contains("no filler", ignoreCase = true))
        assertTrue(prompt.contains("no personal opinions", ignoreCase = true))
        // The old prompt capped length and invited a spoken tool announcement.
        assertFalse(prompt.contains("unless the user asks for detail"))
        assertFalse(prompt.contains("say what"))
    }

    @Test
    fun `the shipped prompt file is versioned and carries the old defaults`() {
        val file = catalog()
        assertTrue("a prompt version drives the install migration", file.version > 0)
        assertTrue(
            "an older default is needed to migrate installs",
            file.knownDefaults.any { it != file.defaultPrompt },
        )
        assertTrue(
            "the pre-3.x prompt is still recognised",
            file.knownDefaults.any { it.contains("concise, friendly personal assistant") },
        )
        assertTrue(
            "the current default is listed so the next bump migrates a stored copy",
            file.knownDefaults.count { it == file.defaultPrompt } == 1,
        )
    }

    @Test
    fun `presets are labelled and a blank preset falls back to the default`() {
        val file = catalog()
        assertTrue(file.presets.size >= 4)
        assertEquals(file.presets.size, file.presets.map { it.label }.distinct().size)
        assertTrue(file.presets.all { it.prompt.isNotBlank() })
        val general = file.presets.single { it.label == "General assistant" }
        assertEquals(file.defaultPrompt, general.prompt)
    }

    @Test
    fun `defaults favour a sensitive gate and a brisk pace`() {
        val settings = AppSettings()
        assertTrue(settings.vadThreshold < 0.5f)
        assertTrue(settings.speechRate >= 1.4)
    }
}
