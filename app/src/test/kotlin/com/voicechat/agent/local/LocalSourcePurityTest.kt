package com.voicechat.agent.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source guard for the M20 local-runtime boundary.
 *
 * Only three files may import a platform (`android.*`/`androidx.*`) or a vendor
 * runtime (`com.google.*`) type: the ML Kit Prompt probe/generator, the LiteRT-LM
 * engine wrapper, and the app-private Android file store. Everything else — the
 * catalog, availability mapping, install lifecycle, adapters, and selection — is
 * pure so it runs as an ordinary JVM unit test with fakes and no device.
 */
class LocalSourcePurityTest {
    private val vendorOrPlatformFiles =
        setOf(
            "MlKitGenAiPromptRuntime.kt",
            "EngineLiteRtLmSessionFactory.kt",
            "AndroidLocalModelFileStore.kt",
        )

    @Test
    fun thePureLocalTypesDoNotImportAndroidOrVendorRuntimeTypes() {
        val directory = locateLocalSourceDirectory()
        val violations = mutableListOf<String>()

        directory.listFiles().orEmpty().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            if (file.name in vendorOrPlatformFiles) return@forEach
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                val forbidden =
                    trimmed.startsWith("import android.") ||
                        trimmed.startsWith("import androidx.") ||
                        trimmed.startsWith("import com.google.")
                if (forbidden) {
                    violations += "${file.name}:${index + 1}: $line"
                }
            }
        }

        assertTrue(
            "platform/vendor imports leaked into pure local types:\n" + violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
    }

    private fun locateLocalSourceDirectory(): File {
        val relative = "src/main/kotlin/com/voicechat/agent/local"
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
