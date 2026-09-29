package com.voicechat.agent.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards Room's exported schema.
 *
 * The app currently has a single conversation schema version, so there is no
 * migration to test. This test proves instead that the schema is exported where
 * the build puts it (a KSP argument) and that the exported version matches the
 * code, so the first real migration starts from a reviewed baseline. See
 * `docs/persistence.md`.
 */
class ConversationSchemaExportTest {
    @Test
    fun schemaForTheCurrentVersionIsExportedWithTheExpectedTables() {
        val schema = locateSchemaFile()
        val json = schema.readText()

        val exportedVersion =
            Regex("\"version\"\\s*:\\s*(\\d+)")
                .find(json)
                ?.groupValues
                ?.get(1)
                ?.toInt()
                ?: throw AssertionError("no version field in exported schema: ${schema.path}")

        assertEquals(ConversationDatabase.VERSION, exportedVersion)
        assertTrue("schema is missing the conversations table", json.contains("conversations"))
        assertTrue("schema is missing the turns table", json.contains("turns"))
        assertTrue("schema is missing the turn conversationId index", json.contains("index_turns_conversationId"))
    }

    @Test
    fun onlyTheCurrentSchemaVersionIsExportedSoNoMigrationsExistYet() {
        val schemaDirectory = locateSchemaFile().parentFile ?: throw AssertionError("schema has no parent directory")

        val exportedVersions =
            schemaDirectory
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension == "json" }
                .map { it.name }
                .sorted()

        assertEquals(listOf("${ConversationDatabase.VERSION}.json"), exportedVersions)
    }

    private fun locateSchemaFile(): File {
        val relative = "schemas/com.voicechat.agent.persistence.ConversationDatabase/${ConversationDatabase.VERSION}.json"
        val workingDirectory = System.getProperty("user.dir") ?: "."
        var directory: File? = File(workingDirectory).absoluteFile
        while (directory != null) {
            val current = directory
            listOf(relative, "app/$relative").forEach { candidatePath ->
                val candidate = File(current, candidatePath)
                if (candidate.isFile) return candidate
            }
            directory = current.parentFile
        }
        throw AssertionError("Could not locate $relative from $workingDirectory")
    }
}
