package com.voicechat.agent.stt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source guard for the STT boundary: the engine configuration, availability
 * mapping, response assembler, and PCM framing must stay platform-free so they
 * run as ordinary JVM unit tests. Only the ML Kit adapter files may import
 * `android.*` / `androidx.*`.
 */
class SttSourcePurityTest {
    private val pureFiles =
        listOf(
            "SttEngine.kt",
            "SttAvailability.kt",
            "SttResultAssembler.kt",
            "PcmFraming.kt",
        )

    @Test
    fun thePlatformFreeSttTypesDoNotImportAndroidOrAndroidx() {
        val directory = locateSttSourceDirectory()
        val violations = mutableListOf<String>()

        pureFiles.forEach { name ->
            val file = File(directory, name)
            assertTrue("missing STT source: $file", file.isFile)
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("import android.") || trimmed.startsWith("import androidx.")) {
                    violations += "${file.name}:${index + 1}: $line"
                }
            }
        }

        assertTrue(
            "Android/AndroidX imports leaked into platform-free STT types:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
    }

    private fun locateSttSourceDirectory(): File {
        val relative = "src/main/kotlin/com/voicechat/agent/stt"
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
