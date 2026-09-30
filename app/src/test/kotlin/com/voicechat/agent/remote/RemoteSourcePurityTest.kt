package com.voicechat.agent.remote

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture guard for the M14 transport: the HTTP client (OkHttp) and the
 * JSON library (kotlinx-serialization) are confined to the `remote` package, and
 * the domain/contracts seam must not depend on the transport at all.
 *
 * This is what keeps "no vendor/HTTP/JSON type in the public contract" true as
 * real adapters appear: the adapter imports `RemoteTransport`/`RemoteJson`, never
 * `okhttp3` or `kotlinx.serialization`.
 *
 * Two files are the recognized HTTP boundaries: the LLM streaming engine and the
 * M10 Smart Turn model download (`OkHttpSmartTurnModelSource.kt`), which reuses
 * OkHttp but stays behind the pure `turn.SmartTurnModelSource` interface.
 */
class RemoteSourcePurityTest {
    private val okHttpFiles = setOf("OkHttpStreamingEngine.kt", "OkHttpSmartTurnModelSource.kt")
    private val jsonFiles = setOf("RemoteJson.kt")

    @Test
    fun theHttpAndJsonLibrariesAreConfinedToTheTransportFiles() {
        val root = locateSourceRoot()
        val violations = mutableListOf<String>()

        root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trimStart()
                    val okHttp = trimmed.startsWith("import okhttp3.") || trimmed.startsWith("import okio.")
                    val json = trimmed.startsWith("import kotlinx.serialization.")
                    if (okHttp && file.name !in okHttpFiles) violations += "${file.name}:${index + 1}: $line"
                    if (json && file.name !in jsonFiles) violations += "${file.name}:${index + 1}: $line"
                }
            }

        assertTrue(
            "an HTTP/JSON library type leaked outside the transport:\n" + violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun theLlmContractAndDomainDoNotDependOnTheTransport() {
        val root = locateSourceRoot()
        val violations = mutableListOf<String>()

        listOf("domain", "contracts").forEach { packageDirectory ->
            root
                .resolve(packageDirectory)
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("import com.voicechat.agent.remote.") ||
                            trimmed.startsWith("import okhttp3.") ||
                            trimmed.startsWith("import kotlinx.serialization.")
                        ) {
                            violations += "${file.name}:${index + 1}: $line"
                        }
                    }
                }
        }

        assertTrue(
            "the contract/domain depends on the transport:\n" + violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
    }

    private fun locateSourceRoot(): File {
        val relative = "src/main/kotlin/com/voicechat/agent"
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
