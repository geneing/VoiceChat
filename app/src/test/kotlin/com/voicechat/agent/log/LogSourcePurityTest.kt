package com.voicechat.agent.log

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture guard for the logging facade: the core must stay pure Kotlin so it
 * runs as ordinary JVM tests and can be unit-tested without a device. Only the
 * platform sinks ([AndroidLogSink] and [AndroidLogging]) may touch `android.*`.
 *
 * This mirrors the `DomainPurityTest` / `SttSourcePurityTest` pattern.
 */
class LogSourcePurityTest {
    private val pureFiles =
        listOf(
            "LogLevel.kt",
            "LogSink.kt",
            "RecordingLogSink.kt",
            "AppLog.kt",
        )

    @Test
    fun thePlatformFreeLogCoreDoesNotImportAndroidOrAndroidx() {
        val directory = locateLogSourceDirectory()
        val violations = mutableListOf<String>()

        pureFiles.forEach { name ->
            val file = File(directory, name)
            assertTrue("missing log source: $file", file.isFile)
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("import android.") || trimmed.startsWith("import androidx.")) {
                    violations += "${file.name}:${index + 1}: $line"
                }
            }
        }

        assertTrue(
            "Android/AndroidX imports leaked into the platform-free log core:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
    }

    private fun locateLogSourceDirectory(): File {
        val relative = "src/main/kotlin/com/voicechat/agent/log"
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
