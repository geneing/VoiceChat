package com.voicechat.assistant.memory

/** What kind of durable fact a memory row holds. */
enum class MemoryKind {
    IDENTITY,
    PREFERENCE,
    PERSON,
    PLACE,
    ROUTINE,
    PROJECT,
    NOTE,
}

/**
 * One durable fact about the user, stored on this device and injected as
 * context. [subject] is the dedupe key inside a [kind], so saying the same thing
 * twice updates the row instead of adding a near duplicate.
 */
data class MemoryFact(
    val id: String,
    val kind: MemoryKind,
    val subject: String,
    val text: String,
    val keywords: String,
    val confidence: Float,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long,
    val useCount: Int,
    val sourceChatId: String? = null,
) {
    val dedupeKey: String get() = "${kind.name}:$subject"
}

/** A fact the extractor (or the model) proposed, not yet written. */
data class MemoryCandidate(
    val kind: MemoryKind,
    val subject: String,
    val text: String,
    val confidence: Float = 0.6f,
    val pinned: Boolean = false,
    val keywords: String = "",
) {
    val dedupeKey: String get() = "${kind.name}:$subject"
}
