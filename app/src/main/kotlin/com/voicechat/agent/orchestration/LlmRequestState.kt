package com.voicechat.agent.orchestration

/**
 * The finalized `REQUEST_STATE` vocabulary for the M04 turn trace (R-0028).
 *
 * M04 defined the `DiagnosticAttribute.REQUEST_STATE` key but left the set of
 * values to the turn orchestrator. This enum is that vocabulary: every place
 * orchestration reports LLM request state now uses one of these stable names, so
 * a trace consumer can rely on the set instead of string matching ad-hoc
 * literals. Values are `name`d in lower case and are part of the persisted trace
 * contract once shipped.
 *
 * `SELECTED` and `STREAMING` are recorded by the recorder helpers
 * (`requestSelected` / `markStreamStarted`); orchestration records the rest
 * through `TurnTraceRecorder.requestState`.
 */
enum class LlmRequestState(
    val wireName: String,
) {
    /** The request's provider/model were resolved and recorded. */
    SELECTED("selected"),

    /** The adapter started emitting; time-to-first-text is measured from here. */
    STREAMING("streaming"),

    /** The provider completed the response normally. */
    COMPLETED("completed"),

    /** The stream stopped without completing (failure, cancellation, or EOF). */
    ENDED("ended"),

    /** The user or orchestrator cancelled the request. */
    CANCELLED("cancelled"),

    /** The request failed with a typed error. */
    FAILED("failed"),
}
