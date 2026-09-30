package com.voicechat.agent.voice

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture guard for M24 (mirrors `OrchestrationPurityTest`).
 *
 * The coordinator, its seams, and its fakes are pure Kotlin so they run under
 * `:app:testDebugUnitTest` with no device. Only the app-boundary
 * `VoiceSessionAssembly.kt` may touch platform types (`Context`,
 * `MlKitSpeechToText`, `MicrophoneAudioCapture`, `OnDeviceTts`); every other file
 * in `voice/` must stay `android.*`/`androidx.*`-free and must not depend on the
 * Compose `ui` package.
 */
class VoiceSourcePurityTest {
    private val forbiddenPrefixes =
        listOf(
            "import android.",
            "import androidx.",
            "import com.voicechat.agent.ui.",
        )

    @Test
    fun voiceCoreImportsNoPlatformOrUiType() {
        val directory = locateVoiceDirectory()
        val files =
            directory
                .listFiles { file -> file.extension == "kt" && file.name != ASSEMBLY_FILE }
                .orEmpty()
        assertTrue("no voice sources found in $directory", files.isNotEmpty())

        val violations = mutableListOf<String>()
        files.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                val forbidden = forbiddenPrefixes.firstOrNull { trimmed.startsWith(it) }
                if (forbidden != null) violations += "${file.name}:${index + 1}: $line"
            }
        }

        assertTrue(
            "a platform or UI type leaked into the voice core:\n" + violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun theOnlyBoundaryFileIsTheAssembly() {
        val names = locateVoiceDirectory().listFiles { file -> file.extension == "kt" }.orEmpty().map { it.name }
        assertTrue("the platform assembly must exist", names.contains(ASSEMBLY_FILE))
        assertTrue(
            "every non-assembly voice file must be pure Kotlin",
            names.filter { it != ASSEMBLY_FILE }.all { name -> !name.contains("android", ignoreCase = true) },
        )
    }

    private fun locateVoiceDirectory(): File {
        val relative = "src/main/kotlin/com/voicechat/agent/voice"
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

    private companion object {
        const val ASSEMBLY_FILE = "VoiceSessionAssembly.kt"
    }
}
