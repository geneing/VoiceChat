package com.voicechat.agent.diagnostics

import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId

/** Kinds of opt-in sensitive content a trace can capture locally. */
enum class TraceContentKind {
    USER_TRANSCRIPT,
    PROMPT,
    ASSISTANT_RESPONSE,
    PROVIDER_RESPONSE,
}

/**
 * One opt-in captured content record.
 *
 * This is the *only* place transcript, prompt, or provider-response text is
 * allowed, and it exists exclusively behind [TraceContentPolicy.enabled]. It is
 * never emitted as a [com.voicechat.agent.contracts.DiagnosticEvent], so the
 * default trace stays content-free (see `docs/turn-tracing.md`).
 */
data class TraceContentRecord(
    val traceId: TraceId,
    val turnId: TurnId?,
    val kind: TraceContentKind,
    val text: String,
    val monotonicTimeNanos: Long,
)

/**
 * Configuration for opt-in local sensitive-content capture.
 *
 * Capture is **off by default**. When enabled it is bounded in both the number
 * of records and the characters per record, so a debugging session cannot grow
 * without limit (see `docs/privacy-and-security.md`, "Local data and logging").
 */
data class TraceContentPolicy(
    val enabled: Boolean = false,
    val maxRecords: Int = DEFAULT_MAX_RECORDS,
    val maxCharactersPerRecord: Int = DEFAULT_MAX_CHARACTERS,
) {
    init {
        require(maxRecords > 0) { "maxRecords must be positive" }
        require(maxCharactersPerRecord > 0) { "maxCharactersPerRecord must be positive" }
    }

    companion object {
        /** Default number of retained content records when capture is enabled. */
        const val DEFAULT_MAX_RECORDS = 64

        /** Default characters retained per content record when capture is enabled. */
        const val DEFAULT_MAX_CHARACTERS = 4096

        /** Capture disabled, the safe default. */
        val DISABLED: TraceContentPolicy = TraceContentPolicy()
    }
}

/** Receives opt-in sensitive content; the default implementation discards it. */
interface TraceContentSink {
    fun capture(record: TraceContentRecord)
}

/** Sink that discards content; the safe default that proves no content is retained. */
object NoOpTraceContentSink : TraceContentSink {
    override fun capture(record: TraceContentRecord) = Unit
}

/**
 * Bounded, in-memory opt-in content store.
 *
 * Capture happens only when [policy] is enabled. Records are truncated to
 * [TraceContentPolicy.maxCharactersPerRecord] and the oldest record is evicted
 * once [TraceContentPolicy.maxRecords] is exceeded. `clear()` must be called
 * when the debugging session ends; this store never persists to disk.
 *
 * Like `InMemoryTraceStore`, this is driven by a single consumer and is not
 * itself thread-safe.
 */
class InMemoryTraceContentStore(
    private val policy: TraceContentPolicy = TraceContentPolicy.DISABLED,
) : TraceContentSink {
    private val records = ArrayDeque<TraceContentRecord>()

    /** True when capture is opted in; false keeps this store empty. */
    val isEnabled: Boolean get() = policy.enabled

    override fun capture(record: TraceContentRecord) {
        if (!policy.enabled) return
        val bounded =
            if (record.text.length > policy.maxCharactersPerRecord) {
                record.copy(text = record.text.take(policy.maxCharactersPerRecord))
            } else {
                record
            }
        records.addLast(bounded)
        while (records.size > policy.maxRecords) {
            records.removeFirst()
        }
    }

    /** Snapshot of retained records, oldest first. */
    fun snapshot(): List<TraceContentRecord> = records.toList()

    /** Drops all retained content. */
    fun clear() {
        records.clear()
    }
}
