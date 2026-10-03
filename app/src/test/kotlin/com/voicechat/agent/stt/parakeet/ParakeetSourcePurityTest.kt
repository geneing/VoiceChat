package com.voicechat.agent.stt.parakeet

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture guard for stream 3: the app-facing adapter, the decode seam, and
 * the EOU signal/detector must stay pure Kotlin/JVM-testable (`android.*`-free),
 * with the ONNX runtime kept in its own files.
 *
 * The parakeet package legitimately contains a platform boundary — stream 2's
 * `OnnxParakeetEouRuntime` and `ParakeetEouModelStore` import `ai.onnxruntime`
 * and `android.*`. The check therefore enumerates the **stream-3 files plus stream
 * 1's pure decode files** rather than the whole directory, so an accidental
 * platform import in the pure seam is caught without flagging the intended
 * boundary.
 */
class ParakeetSourcePurityTest {
    private val bannedPrefixes =
        listOf(
            "import android.",
            "import androidx.",
            "import ai.onnxruntime",
        )

    private val bannedSymbols =
        listOf(
            "AudioRecord",
            "OrtEnvironment",
        )

    /** Files that must compile and run on the JVM with no platform type. */
    private val pureFiles =
        listOf(
            // Stream 3 (this work).
            "ParakeetEouSpeechToText.kt",
            "ParakeetDecodeSession.kt",
            "EndOfUtteranceSignal.kt",
            "EouTurnCompletionDetector.kt",
            "ParakeetEouTurnDetectorFactory.kt",
            // Stream 1's pure decode path, which stream 3 depends on.
            "ParakeetStreamingDecoder.kt",
            "ParakeetEouConfig.kt",
            "ParakeetVocabulary.kt",
            "StreamingMelFrontend.kt",
            "ParakeetEouRuntime.kt",
        )

    @Test
    fun thePureParakeetSeamImportsNoPlatformTypes() {
        val directory = locateParakeetSourceDirectory()
        val violations = mutableListOf<String>()

        pureFiles.forEach { name ->
            val file = File(directory, name)
            if (!file.isFile) return@forEach
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
            "Android/ONNX imports or platform symbols leaked into the pure parakeet seam:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
    }

    private fun locateParakeetSourceDirectory(): File {
        val relative = "src/main/kotlin/com/voicechat/agent/stt/parakeet"
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
