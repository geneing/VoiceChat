package com.voicechat.agent.diagnostics

/**
 * Helpers for keeping credentials and content out of logs and crash metadata.
 *
 * Trace events are content-free by construction (see [com.voicechat.agent.contracts.DiagnosticEvent]),
 * but imperative paths such as an HTTP client, a crash reporter, or a debug
 * log still render values that may be sensitive. Route those values through
 * [redact] so a secret is replaced rather than copied into a log line. The
 * default is to redact: a non-empty value is never echoed.
 */
object Redaction {
    /** Replacement for any redacted value. */
    const val PLACEHOLDER = "[redacted]"

    private val sensitiveHeaderFragments =
        listOf("authorization", "api-key", "apikey", "token", "secret", "password", "credential", "cookie")

    /**
     * Returns [PLACEHOLDER] for any non-empty [value], and an empty string for a
     * null or empty value. The input is never echoed back.
     */
    fun redact(value: String?): String = if (value.isNullOrEmpty()) "" else PLACEHOLDER

    /**
     * Redacts values in [headers] whose name looks sensitive (case-insensitive
     * substring match). Safe headers such as a model name are left untouched.
     */
    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) ->
            if (isSensitiveName(name)) PLACEHOLDER else value
        }

    /** True when [name] identifies a credential-bearing field. */
    fun isSensitiveName(name: String): Boolean {
        val normalized = name.lowercase()
        return sensitiveHeaderFragments.any { normalized.contains(it) }
    }
}
