package com.voicechat.assistant.core

/** Recognise a report only when the final voice transcript starts with the command. */
internal object IssueReportCommand {
    private val prefix = Regex(
        """^\s*(?:save|create)\s+an?\s+issue\s+report\b[\s,.!?:–—-]*""",
        RegexOption.IGNORE_CASE,
    )

    fun description(transcript: String): String? {
        val command = prefix.find(transcript) ?: return null
        return transcript.substring(command.range.last + 1).trim()
    }
}
