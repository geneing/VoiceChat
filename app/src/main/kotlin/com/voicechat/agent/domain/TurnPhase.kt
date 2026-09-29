package com.voicechat.agent.domain

/**
 * Coarse lifecycle of a single voice or text turn.
 *
 * This is a deliberate, conservative seam: the authoritative turn state machine
 * is implemented by orchestration in M21, which may refine the event set. The
 * transitions here exist so adapters and tests share one definition of a legal
 * turn lifecycle and can reject impossible transitions instead of inferring
 * them from UI booleans (see `docs/architecture.md`, "State and interaction
 * model").
 *
 * `IDLE -> LISTENING -> GENERATING -> SPEAKING -> COMPLETED`
 *
 * `INTERRUPTED` is the barge-in branch: assistant playback was cut short, and
 * the same user is expected to continue speaking (`INTERRUPTED -> LISTENING`).
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
            IDLE -> setOf(LISTENING)
            LISTENING -> setOf(GENERATING, CANCELLED, FAILED, IDLE)
            GENERATING -> setOf(SPEAKING, CANCELLED, FAILED)
            SPEAKING -> setOf(COMPLETED, INTERRUPTED, CANCELLED, FAILED)
            INTERRUPTED -> setOf(LISTENING, CANCELLED, FAILED)
            COMPLETED, CANCELLED, FAILED -> emptySet()
        }

    companion object {
        private val TERMINAL_PHASES = setOf(COMPLETED, CANCELLED, FAILED)
    }
}
