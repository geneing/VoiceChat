package com.voicechat.agent.contracts

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture guard for M02's acceptance criterion: the conversation domain
 * and its contracts must not depend on Android or AndroidX types.
 *
 * The check reads the main source set because a compile-level check cannot
 * prove the absence of an import that is never actually needed at runtime.
 */
class DomainPurityTest {
    @Test
    fun domainAndContractPackagesDoNotImportAndroidOrAndroidx() {
        val sourceRoot = locateSourceRoot()
        val violations = mutableListOf<String>()

        listOf("domain", "contracts").forEach { packageDirectory ->
            val directory = File(sourceRoot, packageDirectory)
            assertTrue("missing source directory: $directory", directory.isDirectory)
            directory
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("import android.") || trimmed.startsWith("import androidx.")) {
                            violations += "${file.name}:${index + 1}: $line"
                        }
                    }
                }
        }

        assertTrue(
            "Android/AndroidX imports leaked into the domain or contracts packages:\n" +
                violations.joinToString(separator = "\n"),
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
