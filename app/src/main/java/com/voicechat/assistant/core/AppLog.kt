package com.voicechat.assistant.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

enum class LogCategory {
    TRANSPORT,
    PROTOCOL,
    AUDIO,
    TOOLS,
    SESSION,
    COST,
    UI,
    DB,
}

enum class LogLevel(val priority: Int) {
    VERBOSE(2),
    DEBUG(3),
    INFO(4),
    WARN(5),
    ERROR(6),
}

data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val level: LogLevel,
    val category: LogCategory,
    val message: String,
    val detail: String? = null,
)

/**
 * Lightweight, redacting logger. It is safe to call from any thread and is used
 * by both UI and protocol layers. Payloads are never written verbatim: audio and
 * image base64 blobs are collapsed, and API keys are redacted defensively.
 */
object AppLog {

    private const val MAX_ENTRIES = 1500
    private const val MAX_FILE_BYTES = 1_048_576L
    private const val TAG = "VoiceChat"

    private val counter = AtomicLong(0)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    @Volatile
    var enabledCategories: Set<LogCategory> = LogCategory.entries.toSet()

    @Volatile
    var protocolDump: Boolean = true

    @Volatile
    var fileLogging: Boolean = false

    @Volatile
    private var filesDir: File? = null

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "app-log-writer").apply { isDaemon = true }
    }

    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun attach(context: Context) {
        filesDir = File(context.filesDir, "logs")
    }

    fun configure(categories: Set<LogCategory>, protocolDump: Boolean, fileLogging: Boolean) {
        enabledCategories = categories
        this.protocolDump = protocolDump
        this.fileLogging = fileLogging
    }

    fun verbose(category: LogCategory, message: String, detail: String? = null) =
        log(category, LogLevel.VERBOSE, message, detail)

    fun debug(category: LogCategory, message: String, detail: String? = null) =
        log(category, LogLevel.DEBUG, message, detail)

    fun info(category: LogCategory, message: String, detail: String? = null) =
        log(category, LogLevel.INFO, message, detail)

    fun warn(category: LogCategory, message: String, detail: String? = null) =
        log(category, LogLevel.WARN, message, detail)

    fun error(category: LogCategory, message: String, detail: String? = null) =
        log(category, LogLevel.ERROR, message, detail)

    fun log(
        category: LogCategory,
        level: LogLevel,
        message: String,
        detail: String? = null,
    ) {
        if (category !in enabledCategories) return
        val safeMessage = redact(message)
        val safeDetail = detail?.let { redact(it) }
        val entry = LogEntry(
            id = counter.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            level = level,
            category = category,
            message = safeMessage,
            detail = safeDetail,
        )
        runCatching {
            Log.println(level.priority, TAG, buildString {
                append(category.name)
                append(": ")
                append(safeMessage)
                if (safeDetail != null) {
                    append(" | ")
                    append(safeDetail)
                }
            })
        }
        _entries.update { current ->
            val next = current + entry
            if (next.size > MAX_ENTRIES) next.subList(next.size - MAX_ENTRIES, next.size) else next
        }
        if (fileLogging) writeToFile(entry)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    fun export(): String = buildString {
        _entries.value.forEach { entry ->
            append(timeFormat.get()!!.format(Date(entry.timestamp)))
            append(' ')
            append(entry.level.name.padEnd(7))
            append(entry.category.name.padEnd(10))
            append(entry.message)
            entry.detail?.let {
                append('\n')
                append("    ")
                append(it.replace("\n", "\n    "))
            }
            append('\n')
        }
    }

    /**
     * Collapses binary payloads and credentials so dumped events stay readable
     * and small. Base64 blobs can be hundreds of kilobytes.
     */
    fun redact(text: String): String {
        var result = text
        result = BINARY_FIELD.replace(result) { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            "\"$key\":\"<${value.length} chars>\""
        }
        result = BINARY_JSON_FIELD.replace(result) { match ->
            "\"${match.groupValues[1]}\":\"<${match.groupValues[2].length} chars>\""
        }
        result = BEARER.replace(result, "Bearer <redacted>")
        result = SK_KEY.replace(result, "sk-<redacted>")
        return result
    }

    private fun writeToFile(entry: LogEntry) {
        val dir = filesDir ?: return
        writer.execute {
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "app.log")
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    val previous = File(dir, "app.log.1")
                    if (previous.exists()) previous.delete()
                    file.renameTo(previous)
                }
                val line = buildString {
                    append(timeFormat.get()!!.format(Date(entry.timestamp)))
                    append(' ')
                    append(entry.level.name)
                    append(' ')
                    append(entry.category.name)
                    append(' ')
                    append(entry.message)
                    entry.detail?.let { append(" | ").append(it.replace("\n", " ")) }
                    append('\n')
                }
                file.appendText(line)
            }
        }
    }

    private val BINARY_FIELD = Regex("\"(audio|image)\"\\s*:\\s*\"([A-Za-z0-9+/=]{64,})\"")
    private val BINARY_JSON_FIELD = Regex("\"(delta|data)\"\\s*:\\s*\"([A-Za-z0-9+/=]{64,})\"")
    private val BEARER = Regex("Bearer\\s+[A-Za-z0-9._\\-]+")
    private val SK_KEY = Regex("sk-[A-Za-z0-9]{16,}")
}
