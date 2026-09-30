package com.voicechat.agent.contracts

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture guard for the M12 acceptance criterion "the public contract
 * exposes no vendor SDK type".
 *
 * `DomainPurityTest` already proves the contract packages import no
 * `android.*`/`androidx.*`. This check goes further: an LLM contract file must
 * not import a vendor SDK, an HTTP/JSON client, or a provider package either, so
 * "no vendor type in the public contract" is enforced rather than reviewed.
 */
class LlmContractPurityTest {
    private val llmContractFiles =
        listOf(
            "LanguageModel.kt",
            "LlmStreamConsumer.kt",
        )

    /**
     * Package prefixes that must never appear in an LLM contract import.
     *
     * Each is a real concern this project will meet in M14–M20: the ML Kit GenAI
     * STT SDK (already a dependency), OkHttp/Retrofit for transports, and a
     * kotlinx-serialization/JSON payload type. None may leak into the seam.
     */
    private val forbiddenPrefixes =
        listOf(
            "import android.",
            "import androidx.",
            "import com.google.",
            "import okhttp3.",
            "import retrofit2.",
            "import kotlinx.serialization.",
            "import org.json.",
            "import java.net.",
            "import javax.net.",
            "import java.io.",
        )

    @Test
    fun theLlmContractImportsNoVendorSdkTransportOrPlatformType() {
        val directory = locateContractsDirectory()
        val violations = mutableListOf<String>()

        llmContractFiles.forEach { name ->
            val file = File(directory, name)
            assertTrue("missing LLM contract source: $file", file.isFile)
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                val forbidden = forbiddenPrefixes.firstOrNull { trimmed.startsWith(it) }
                if (forbidden != null) violations += "$name:${index + 1}: $line"
            }
        }

        assertTrue(
            "a vendor/platform type leaked into the LLM contract:\n" + violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
    }

    private fun locateContractsDirectory(): File {
        val relative = "src/main/kotlin/com/voicechat/agent/contracts"
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
