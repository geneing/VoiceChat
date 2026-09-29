package com.voicechat.agent.replay

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Acceptance guard: replay must need no microphone, network, credentials, or
 * Android framework, so the whole layer stays pure JVM.
 *
 * The check reads the source because an unused import or an accidental
 * platform type is otherwise invisible until it runs on a device.
 */
class ReplayIsolationTest {
    private val bannedImports =
        listOf(
            "import android.",
            "import androidx.",
            "import java.net.",
            "import okhttp3.",
            "import okio.",
            "import retrofit2.",
        )

    private val bannedSymbols =
        listOf(
            "AudioRecord",
            "MediaRecorder",
            "android.speech.tts",
            "Socket",
        )

    @Test
    fun theReplayLayerHasNoPlatformOrNetworkDependencies() {
        val violations = scan(ReplayTestSupport.mainReplaySourceDirectory())

        assertTrue(
            "replay sources must not use the Android framework or the network:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun theReplayTestsHaveNoPlatformOrNetworkDependencies() {
        val violations = scan(ReplayTestSupport.testReplaySourceDirectory())

        assertTrue(
            "replay tests must not use the Android framework or the network:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
    }

    private fun scan(directory: File): List<String> {
        assertTrue("missing source directory: $directory", directory.isDirectory)
        val violations = mutableListOf<String>()
        directory
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != GUARD_FILE_NAME }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    val importBanned = bannedImports.any { trimmed.startsWith(it) }
                    val symbolBanned = bannedSymbols.any { trimmed.contains(it) }
                    if (importBanned || symbolBanned) {
                        violations += "${file.name}:${index + 1}: $line"
                    }
                }
            }
        return violations
    }

    private companion object {
        // This guard file itself names the banned markers, so it must not scan itself.
        const val GUARD_FILE_NAME: String = "ReplayIsolationTest.kt"
    }
}
