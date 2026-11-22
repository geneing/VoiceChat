package com.voicechat.assistant.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Process
import com.voicechat.assistant.BuildConfig
import com.voicechat.assistant.data.model.Message
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class IssueReportSnapshot(
    val capturedAt: Long,
    val source: String,
    val description: String,
    val session: String,
    val messages: List<Message>,
    val assistantPartial: String,
    val appLogs: List<LogEntry>,
    val apiKeyOverride: String,
    val protocolDump: Boolean,
)

/** Commits complete report folders to app-private storage and exports copies on demand. */
internal class IssueReportStore(private val context: Context) {
    private val root get() = File(context.noBackupFilesDir, "issue-reports")

    suspend fun save(snapshot: IssueReportSnapshot): String = withContext(Dispatchers.IO) {
        val id = "${snapshot.capturedAt}-${UUID.randomUUID()}"
        val directory = File(root, ".$id")
        if (!directory.mkdirs()) throw IOException("Could not create report directory")
        val credentials = listOf(
            snapshot.apiKeyOverride,
            BuildConfig.QWEN_API_KEY,
            BuildConfig.EXA_API_KEY,
            BuildConfig.BRAVE_SEARCH_API_KEY,
        ).map(String::trim).filter(String::isNotEmpty).distinct().sortedByDescending(String::length)
        fun write(name: String, content: String) {
            val redacted = credentials.fold(content) { value, key -> value.replace(key, "<redacted>") }
            File(directory, name).writeText(AppLog.redact(redacted))
        }

        try {
            val logs = snapshot.appLogs.filter {
                it.timestamp in (snapshot.capturedAt - WINDOW_MS)..snapshot.capturedAt
            }.sortedBy { it.timestamp }
            val network = networkSnapshot()
            write(
                "issue.md",
                buildString {
                    appendLine("# VoiceChat issue report")
                    appendLine()
                    appendLine("Captured at ${Instant.ofEpochMilli(snapshot.capturedAt)}")
                    appendLine("Trigger: ${snapshot.source}")
                    appendLine("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${Build.VERSION.SDK_INT}")
                    appendLine()
                    appendLine(snapshot.session)
                    appendLine()
                    appendLine("## Description")
                    appendLine()
                    appendLine(snapshot.description.take(MAX_DESCRIPTION_CHARS).ifBlank { "No description supplied." })
                },
            )
            write("app.log", formatLogs(logs))
            write("llm-interaction.log", interactionLog(snapshot, logs))
            write(
                "network.log",
                "$network\n\nRecent transport and Wi-Fi events from enabled app logging\n" +
                    formatLogs(logs.filter { it.category == LogCategory.TRANSPORT }),
            )
            write("android-logcat.log", logcat(snapshot.capturedAt))
            if (!directory.renameTo(File(root, id))) throw IOException("Could not commit report")
            id
        } finally {
            // The staging name is never listed as a saved report.
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().filter { it.isDirectory && REPORT_ID.matches(it.name) }
            .map(File::getName).sortedDescending()
    }

    suspend fun export(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        require(REPORT_ID.matches(id))
        val report = File(root, id)
        val files = FILE_NAMES.map { File(report, it) }
        if (!files.all(File::isFile)) throw IOException("The report is incomplete or missing")
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Could not open the destination")
        ZipOutputStream(output.buffered()).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(ZipEntry("$id/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun formatLogs(logs: List<LogEntry>): String =
        if (logs.isEmpty()) "No enabled app log entries retained for the preceding five minutes.\n"
        else logs.joinToString("\n", postfix = "\n") { entry ->
            "${Instant.ofEpochMilli(entry.timestamp)} ${entry.level} ${entry.category} " +
                entry.message.take(MAX_LOG_CHARS) +
                entry.detail?.let { " | " + it.take(MAX_LOG_CHARS) }.orEmpty()
        }

    private fun interactionLog(snapshot: IssueReportSnapshot, logs: List<LogEntry>): String = buildString {
        appendLine("Recent conversation, tool outputs and reasoning cards from the active chat")
        if (snapshot.messages.isEmpty()) appendLine("No recent messages were available.")
        snapshot.messages.forEach { message ->
            appendLine("${Instant.ofEpochMilli(message.createdAt)} ${message.role} ${message.kind}" +
                " inputTokens=${message.inputTokens} outputTokens=${message.outputTokens} ttftMs=${message.ttftMs}")
            appendLine(message.text.take(MAX_LOG_CHARS))
        }
        if (snapshot.assistantPartial.isNotBlank()) {
            appendLine("Assistant response in progress")
            appendLine(snapshot.assistantPartial.take(MAX_LOG_CHARS))
        }
        appendLine()
        appendLine("Recent session, tool and reasoning events. Protocol dump enabled at capture: ${snapshot.protocolDump}")
        append(formatLogs(logs.filter { it.category in LLM_CATEGORIES }))
    }

    private fun networkSnapshot(): String = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager?.activeNetwork
        val capabilities = active?.let { manager?.getNetworkCapabilities(it) }
        if (active == null || capabilities == null) {
            "No active default network capabilities were available at capture."
        } else {
            "Default network $active at ${Instant.now()}\n${describeNetwork(capabilities)}"
        }
    } catch (_: SecurityException) {
        "Default network capabilities were unavailable to the app."
    }

    private fun logcat(capturedAt: Long): String {
        val header = "Android Logcat visible to VoiceChat for PID ${Process.myPid()} over the preceding five minutes.\n" +
            "Device-wide Android, Wi-Fi and radio logs require ADB or privileged access.\n"
        return try {
            val process = ProcessBuilder(
                "/system/bin/logcat", "-b", "main", "-b", "system", "-b", "crash",
                "--pid=${Process.myPid()}", "-v", "epoch", "-t", "500",
            ).redirectErrorStream(true).start()
            // Drain the pipe while the command runs. Raw Logcat never touches disk.
            val reader = FutureTask {
                process.inputStream.bufferedReader().useLines { stream ->
                    stream.mapNotNull { line ->
                        val seconds = line.trimStart().substringBefore(' ').toDoubleOrNull()
                        val timestamp = seconds?.times(1000)?.toLong()
                        line.take(MAX_LOG_CHARS).takeIf {
                            timestamp != null && timestamp in (capturedAt - WINDOW_MS)..capturedAt
                        }
                    }.take(500).toList()
                }
            }
            try {
                Thread(reader, "issue-report-logcat").apply { isDaemon = true }.start()
                val lines = reader.get(3, TimeUnit.SECONDS)
                when {
                    !process.waitFor(1, TimeUnit.SECONDS) -> header + "Logcat timed out.\n"
                    process.exitValue() != 0 -> header + "Logcat exited with code ${process.exitValue()}.\n"
                    lines.isEmpty() -> header + "No matching entries.\n"
                    else -> header + lines.joinToString("\n", postfix = "\n")
                }
            } finally {
                process.destroyForcibly()
                reader.cancel(true)
            }
        } catch (_: TimeoutException) {
            header + "Logcat timed out.\n"
        } catch (_: ExecutionException) {
            header + "Logcat output could not be read.\n"
        } catch (_: IOException) {
            header + "Logcat could not be read.\n"
        } catch (_: SecurityException) {
            header + "Logcat was unavailable to the app.\n"
        }
    }

    companion object {
        private const val WINDOW_MS = 5 * 60 * 1000L
        private const val MAX_LOG_CHARS = 4096
        private const val MAX_DESCRIPTION_CHARS = 8000
        private val REPORT_ID = Regex("""[0-9]{13}-[a-f0-9-]{36}""")
        private val FILE_NAMES = listOf("issue.md", "app.log", "llm-interaction.log", "network.log", "android-logcat.log")
        private val LLM_CATEGORIES = setOf(LogCategory.SESSION, LogCategory.PROTOCOL, LogCategory.HANDOFF, LogCategory.TOOLS)

        fun describeNetwork(capabilities: NetworkCapabilities): String = buildString {
            append("wifi=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} ")
            append("cellular=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)} ")
            append("ethernet=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)} ")
            append("vpn=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)} ")
            append("validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} ")
            append("metered=${!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)} ")
            append("captivePortal=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)}")
            if (Build.VERSION.SDK_INT >= 29 && capabilities.signalStrength != Int.MIN_VALUE) {
                append(" signalStrength=${capabilities.signalStrength}")
            }
        }
    }
}
