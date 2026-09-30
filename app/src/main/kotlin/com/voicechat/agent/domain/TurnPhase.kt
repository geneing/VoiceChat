package com.voicechat.agent.domain

/**
 * Coarse lifecycle of a single voice or text turn.
 *
 * This is the shared, platform-free vocabulary the M21 orchestration state
 * machine drives and every adapter/test agrees on, so an impossible lifecycle
 * is rejected instead of being inferred from UI booleans (see
 * `docs/architecture.md`, "State and interaction model", and
 * `docs/orchestration.md`).
 *
 * Manual text starts directly at [GENERATING] (there is no listening stage), so
 * `IDLE -> GENERATING` is legal. A voice turn listens first and commits a final
 * transcript: `IDLE -> LISTENING -> GENERATING`. Streaming TTS moves
 * `GENERATING -> SPEAKING` while generation may still be in flight, and a
 * text-only turn completes straight from [GENERATING].
 *
 * `INTERRUPTED` is the barge-in branch: assistant output was cut short, and the
 * same user is expected to continue speaking (`INTERRUPTED -> LISTENING`).
 * `COMPLETED`, `CANCELLED`, and `FAILED` are terminal.
 */
enum class TurnPhase {
    IDLE,
    LISTENING,
    GENERATING,
    SPEAKING,
    COMPLETED,
    INTERRUPTED,
    CANCELLED,
    FAILED,
    ;

    /** True when no further phase change is legal. */
    val isTerminal: Boolean get() = this in TERMINAL_PHASES

    /** True when [next] is a legal successor of this phase. */
    fun canTransitionTo(next: TurnPhase): Boolean = next in allowedTransitions()

    /**
     * Returns [next] when the transition is legal, otherwise throws.
     *
     * @throws IllegalArgumentException when [canTransitionTo] is false.
     */
    fun transitionTo(next: TurnPhase): TurnPhase {
        require(canTransitionTo(next)) { "Illegal turn phase transition: $this -> $next" }
        return next
    }

    private fun allowedTransitions(): Set<TurnPhase> =
        when (this) {
            // Manual text may generate without a listening stage; voice listens first.
            IDLE -> setOf(LISTENING, GENERATING)

            LISTENING -> setOf(GENERATING, CANCELLED, FAILED, IDLE)

            // A streamed reply may complete before any speech (text-only) or be
            // interrupted by barge-in before the first chunk is audible.
            GENERATING -> setOf(SPEAKING, COMPLETED, INTERRUPTED, CANCELLED, FAILED)

            SPEAKING -> setOf(COMPLETED, INTERRUPTED, CANCELLED, FAILED)

            INTERRUPTED -> setOf(LISTENING, CANCELLED, FAILED)

            COMPLETED, CANCELLED, FAILED -> emptySet()
        }

    companion object {
        private val TERMINAL_PHASES = setOf(COMPLETED, CANCELLED, FAILED)
    }
}
