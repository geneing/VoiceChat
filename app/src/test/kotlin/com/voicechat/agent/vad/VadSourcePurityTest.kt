package com.voicechat.agent.vad

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture guard for M09: the VAD/endpointing logic must stay pure Kotlin
 * (`android.*`-free) and JVM-testable, with any platform plumbing kept in
 * `audio/`. The check reads the source because an accidental platform import is
 * otherwise invisible until it runs on a device.
 */
class VadSourcePurityTest {
    private val bannedPrefixes =
        listOf(
            "import android.",
            "import androidx.",
        )

    private val bannedSymbols =
        listOf(
            "AudioRecord",
            "MediaRecorder",
        )

    @Test
    fun theVadPackageImportsNoAndroidOrAndroidXTypes() {
        val directory = locateVadSourceDirectory()
        val violations = mutableListOf<String>()

        directory
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trimStart()
                    val importBanned = bannedPrefixes.any { trimmed.startsWith(it) }
                    val symbolBanned = bannedSymbols.any { trimmed.contains(it) }
                    if (importBanned || symbolBanned) {
                        violations += "${file.name}:${index + 1}: $line"
                    }
                }
            }

        assertTrue(
            "Android/AndroidX imports or platform symbols leaked into the vad package:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
    }

    private fun locateVadSourceDirectory(): File {
        val relative = "src/main/kotlin/com/voicechat/agent/vad"
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
