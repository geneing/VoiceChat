package com.voicechat.agent.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture guard for M22: the settings model, option building, validation,
 * authorization lifecycle, and store logic must stay platform-free so they run as
 * ordinary JVM tests. Exactly one file — the DataStore-backed store — may import
 * `android.*` / `androidx.*`.
 *
 * This mirrors `CredentialSourcePurityTest` / `SttSourcePurityTest`.
 */
class SettingsSourcePurityTest {
    private val allowedPlatformFiles = setOf("PreferencesSettingsStore.kt")

    @Test
    fun onlyTheDataStoreImplementationImportsAndroidOrAndroidx() {
        val directory = locateSettingsSourceDirectory()
        val violations = mutableListOf<String>()
        val platformFiles = mutableListOf<String>()

        directory
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                var platformImports = false
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("import android.") || trimmed.startsWith("import androidx.")) {
                        platformImports = true
                        if (file.name !in allowedPlatformFiles) {
                            violations += "${file.name}:${index + 1}: $line"
                        }
                    }
                }
                if (platformImports) platformFiles += file.name
            }

        assertTrue(
            "Android/AndroidX imports leaked outside PreferencesSettingsStore.kt:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
        assertEquals(
            "only the DataStore implementation may be platform-bound",
            allowedPlatformFiles,
            platformFiles.toSet(),
        )
    }

    private fun locateSettingsSourceDirectory(): File {
        val relative = "src/main/kotlin/com/voicechat/agent/settings"
        val workingDirectory = System.getProperty("user.dir") ?: "."
        var directory: File? = File(workingDirectory).absoluteFile
        while (directory != null) {
            val current = directory
            listOf(relative, "app/$relative").forEach { candidatePath ->
                val candidate = File(current, candidatePath)
                if (candidate.isDirectory) return candidate
            }
            directory = current.parentFile
        }
        throw AssertionError("Could not locate $relative from $workingDirectory")
    }
}
