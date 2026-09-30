package com.voicechat.agent.orchestration

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture guard for M21: the turn state machine and orchestrator are
 * pure-Kotlin and UI-free.
 *
 * `TurnStateMachine` runs under `:app:testDebugUnitTest` with no device, so it
 * must not reach into `android.*`/`androidx.*`, and the pipeline must not depend
 * on the Compose `ui` package (N21 acceptance: "No UI or vendor code directly
 * controls pipeline internals"). This check enforces both rather than relying on
 * review.
 */
class OrchestrationPurityTest {
    private val forbiddenPrefixes =
        listOf(
            "import android.",
            "import androidx.",
            "import com.voicechat.agent.ui.",
        )

    @Test
    fun orchestrationImportsNoPlatformOrUiType() {
        val directory = locateOrchestrationDirectory()
        val files = directory.listFiles { file -> file.extension == "kt" }.orEmpty()
        assertTrue("no orchestration sources found in $directory", files.isNotEmpty())

        val violations = mutableListOf<String>()
        files.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                val forbidden = forbiddenPrefixes.firstOrNull { trimmed.startsWith(it) }
                if (forbidden != null) violations += "${file.name}:${index + 1}: $line"
            }
        }

        assertTrue(
            "a platform or UI type leaked into orchestration:\n" + violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
    }

    private fun locateOrchestrationDirectory(): File {
        val relative = "src/main/kotlin/com/voicechat/agent/orchestration"
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
