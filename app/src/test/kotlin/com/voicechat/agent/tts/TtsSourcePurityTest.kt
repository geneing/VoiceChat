package com.voicechat.agent.tts

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source guard for the TTS boundary: the voice policy, engine seam, contract
 * adapter, accounting, and route kinds must stay platform-free so they run as
 * ordinary JVM unit tests. Only `AndroidTtsEngine.kt`, `AndroidTtsOutputRoute.kt`,
 * and `OnDeviceTts.kt` may import `android.*` / `androidx.*`.
 */
class TtsSourcePurityTest {
    private val pureFiles =
        listOf(
            "TtsVoice.kt",
            "TtsEngine.kt",
            "TtsOutputRoute.kt",
            "EngineTextToSpeech.kt",
            "TtsPlaybackAccounting.kt",
        )

    @Test
    fun thePlatformFreeTtsTypesDoNotImportAndroidOrAndroidx() {
        val directory = locateTtsSourceDirectory()
        val violations = mutableListOf<String>()

        pureFiles.forEach { name ->
            val file = File(directory, name)
            assertTrue("missing TTS source: $file", file.isFile)
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("import android.") || trimmed.startsWith("import androidx.")) {
                    violations += "${file.name}:${index + 1}: $line"
                }
            }
        }

        assertTrue(
            "Android/AndroidX imports leaked into platform-free TTS types:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
    }

    private fun locateTtsSourceDirectory(): File {
        val relative = "src/main/kotlin/com/voicechat/agent/tts"
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
