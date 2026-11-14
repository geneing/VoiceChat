package com.voicechat.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCatalogTest {

    @Test
    fun `a preset without a prompt applies the default`() {
        val catalog = PromptCatalog.parse(
            """{"version":3,"default":"be brief","presets":[{"label":"Mine"},{"label":"Other","prompt":"be kind"}]}""",
        )
        assertEquals(3, catalog!!.version)
        assertEquals("be brief", catalog.defaultPrompt)
        assertEquals(listOf("be brief", "be kind"), catalog.presets.map { it.prompt })
    }

    @Test
    fun `future fields are ignored instead of failing the file`() {
        val catalog = PromptCatalog.parse(
            """{"version":1,"default":"d","owner":"x","presets":[{"label":"A","prompt":"p","icon":"star"}]}""",
        )
        assertEquals("p", catalog!!.presets.single().prompt)
    }

    @Test
    fun `a file without a default prompt is rejected`() {
        assertNull(PromptCatalog.parse("""{"version":1,"presets":[]}"""))
        assertNull(PromptCatalog.parse("""{"version":1,"default":"   "}"""))
    }

    @Test
    fun `malformed json is rejected rather than throwing`() {
        assertNull(PromptCatalog.parse("{not json"))
        assertNull(PromptCatalog.parse("""{"version":"one","default":"d"}"""))
    }

    @Test
    fun `a preset without a label is dropped`() {
        val catalog = PromptCatalog.parse(
            """{"version":1,"default":"d","presets":[{"label":" "},{"label":"Keep","prompt":"p"}]}""",
        )
        assertEquals(listOf("Keep"), catalog!!.presets.map { it.label })
    }

    @Test
    fun `an unversioned file reports version zero so it never overrides settings`() {
        val catalog = PromptCatalog.parse("""{"default":"d"}""")
        assertEquals(0, catalog!!.version)
        assertTrue(catalog.knownDefaults.isEmpty())
    }
}
