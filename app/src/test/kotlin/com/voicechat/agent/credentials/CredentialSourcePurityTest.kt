package com.voicechat.agent.credentials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture guard for M13: the credential and provider capability logic must
 * stay platform-free so it runs as ordinary JVM tests. Exactly one file — the
 * AndroidKeyStore implementation — may import `android.*` / `androidx.*`.
 *
 * This mirrors `DomainPurityTest` / `LogSourcePurityTest` / `SttSourcePurityTest`.
 */
class CredentialSourcePurityTest {
    private val allowedPlatformFiles = setOf("AndroidKeystoreCredentialStore.kt")

    @Test
    fun onlyTheKeystoreImplementationImportsAndroidOrAndroidx() {
        val credentialsDirectory = locateSourceDirectory("credentials")
        val providersDirectory = locateSourceDirectory("providers")
        val violations = mutableListOf<String>()
        val platformFiles = mutableListOf<String>()

        listOf(credentialsDirectory, providersDirectory).forEach { directory ->
            directory
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    var platformImports = false
                    file.readLines().forEachIndexed { index, line ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("import android.") || trimmed.startsWith("import androidx.")) {
                            platformImports = true
                            if (file.name !in allowedPlatformFiles) {
                                violations += "${file.name}:${index + 1}: $line"
                            }
                        }
                    }
                    if (platformImports) platformFiles += file.name
                }
        }

        assertTrue(
            "Android/AndroidX imports leaked outside AndroidKeystoreCredentialStore.kt:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
        assertEquals(
            "only the AndroidKeyStore implementation may be platform-bound",
            allowedPlatformFiles,
            platformFiles.toSet(),
        )
    }

    @Test
    fun theProviderRegistryIsEntirelyPlatformFree() {
        val providersDirectory = locateSourceDirectory("providers")
        val violations = mutableListOf<String>()

        providersDirectory
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

        assertTrue(
            "the provider capability registry must be platform-free:\n" + violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
    }

    private fun locateSourceDirectory(packageName: String): File {
        val relative = "src/main/kotlin/com/voicechat/agent/$packageName"
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
