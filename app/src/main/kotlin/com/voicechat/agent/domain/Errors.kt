package com.voicechat.agent.domain

/**
 * Capability area an error originated from.
 *
 * Grouping the stable [ErrorCode]s by category lets callers handle failures
 * (for example "retry the LLM request") without depending on a provider SDK or
 * a platform exception type.
 */
enum class ErrorCategory {
    AUDIO,
    SPEECH_TO_TEXT,
    TURN_DETECTION,
    LANGUAGE_MODEL,
    TEXT_TO_SPEECH,
    MODEL,
    PERSISTENCE,
    UNKNOWN,
}

/**
 * Stable, serializable error identifiers.
 *
 * The enum names are part of the persisted/traced contract: persistence and
 * trace export store [name], so codes must not be renamed once shipped.
 * [defaultRetryable] is only a default hint; a caller may override it on a
 * specific [VoiceAgentError].
 */
enum class ErrorCode(
    val category: ErrorCategory,
    val defaultRetryable: Boolean,
) {
    AUDIO_PERMISSION_DENIED(ErrorCategory.AUDIO, true),
    AUDIO_DEVICE_UNAVAILABLE(ErrorCategory.AUDIO, true),
    AUDIO_CAPTURE_FAILED(ErrorCategory.AUDIO, true),
    STT_UNAVAILABLE(ErrorCategory.SPEECH_TO_TEXT, false),
    STT_MODEL_NOT_READY(ErrorCategory.SPEECH_TO_TEXT, true),
    STT_RECOGNITION_FAILED(ErrorCategory.SPEECH_TO_TEXT, true),
    TURN_DETECTION_UNAVAILABLE(ErrorCategory.TURN_DETECTION, false),
    TURN_DETECTION_FAILED(ErrorCategory.TURN_DETECTION, true),
    LLM_NOT_CONFIGURED(ErrorCategory.LANGUAGE_MODEL, false),
    LLM_AUTHENTICATION_FAILED(ErrorCategory.LANGUAGE_MODEL, false),
    LLM_RATE_LIMITED(ErrorCategory.LANGUAGE_MODEL, true),
    LLM_TIMEOUT(ErrorCategory.LANGUAGE_MODEL, true),
    LLM_NETWORK_FAILED(ErrorCategory.LANGUAGE_MODEL, true),
    LLM_MALFORMED_RESPONSE(ErrorCategory.LANGUAGE_MODEL, true),
    LLM_UNAVAILABLE(ErrorCategory.LANGUAGE_MODEL, true),
    LLM_REQUEST_FAILED(ErrorCategory.LANGUAGE_MODEL, true),

    /**
     * The consumer cancelled the request. It is the typed code for a cancelled
     * stream event; orchestration normally treats `CancellationException` as the
     * signal and does not surface this code as an error (added in M12).
     */
    LLM_CANCELLED(ErrorCategory.LANGUAGE_MODEL, false),

    /**
     * The request asked for something the selected provider/model does not
     * support (for example a reasoning level outside its declared capability).
     * Retrying the identical request cannot succeed, so it is not retryable
     * (added in M12).
     */
    LLM_INVALID_REQUEST(ErrorCategory.LANGUAGE_MODEL, false),
    TTS_NO_ON_DEVICE_VOICE(ErrorCategory.TEXT_TO_SPEECH, false),
    TTS_SYNTHESIS_FAILED(ErrorCategory.TEXT_TO_SPEECH, true),
    TTS_PLAYBACK_FAILED(ErrorCategory.TEXT_TO_SPEECH, true),
    MODEL_UNAVAILABLE(ErrorCategory.MODEL, true),
    MODEL_CORRUPT(ErrorCategory.MODEL, true),
    MODEL_DOWNLOAD_FAILED(ErrorCategory.MODEL, true),
    PERSISTENCE_FAILED(ErrorCategory.PERSISTENCE, true),
    UNKNOWN(ErrorCategory.UNKNOWN, false),
}

/**
 * A typed, platform-free description of a failure.
 *
 * Adapters translate platform/provider exceptions into this value at the
 * boundary so vendor types never reach the domain. [detail] is optional human
 * context and must be safe to log and persist: it must not contain credentials,
 * raw audio, full transcripts, prompts, or unredacted provider responses (see
 * `docs/privacy-and-security.md`). The underlying platform cause belongs in the
 * diagnostics trace, not here.
 */
data class VoiceAgentError(
    val code: ErrorCode,
    val detail: String? = null,
    val retryable: Boolean = code.defaultRetryable,
) {
    val category: ErrorCategory get() = code.category
}

/**
 * Throwable carrier for a [VoiceAgentError].
 *
 * The domain prefers returning/emitting errors, but persistence and imperative
 * adapter code may need to throw. The message is the stable [ErrorCode] name,
 * never [VoiceAgentError.detail], so exception text cannot leak sensitive data;
 * an optional platform [cause] is kept for in-process diagnostics only.
 */
class VoiceAgentException(
    val error: VoiceAgentError,
    cause: Throwable? = null,
) : Exception(error.code.name, cause)
