package com.voicechat.agent.vad

import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.domain.AudioFrame

/** Stable reason a speech-activity transition fired; recorded in diagnostics. */
enum class VadReason {
    /** Frame RMS crossed the onset threshold, so speech started. */
    ONSET_RMS,

    /** Frame RMS stayed below the hangover threshold for the pause window. */
    PAUSE_SILENCE,

    /** Onset-qualifying energy returned after a candidate pause, so speech resumed. */
    RESUME_RMS,
}

/** One speech-activity transition and the reason it was produced. */
data class VadTransition(
    val activity: SpeechActivity,
    val reason: VadReason,
    val atOffsetMillis: Long,
)

/**
 * Frame-driven state machine behind every M09 speech-activity decision.
 *
 * It is deliberately pure Kotlin: it consumes [AudioFrame]s (the same frame
 * shape live capture and replay produce) and emits transitions, with no
 * Android, coroutine, or clock dependency. Both the fast
 * [EnergyVoiceActivityDetector] onset path and the bounded endpoint policy drive
 * an instance of this class, so onset behavior is identical everywhere.
 *
 * The transition boundaries are exactly the M02 [SpeechActivity] values: onset
 * ([VadReason.ONSET_RMS]), candidate pause ([VadReason.PAUSE_SILENCE]), and
 * resume ([VadReason.RESUME_RMS]). Deciding whether a pause ends a thought is
 * *not* this class's job.
 */
class VadStateMachine(
    private val config: VadConfig,
) {
    private enum class State { SILENCE, SPEECH, PAUSE }

    private var state = State.SILENCE
    private var onsetRun = 0
    private var pauseRun = 0
    private var elapsedNanos = 0L

    /** True while speech is considered active (onset seen, not yet paused). */
    val isSpeechActive: Boolean get() = state == State.SPEECH

    /** True after a candidate pause and before speech resumes or the turn ends. */
    val isPaused: Boolean get() = state == State.PAUSE

    /** Offset in milliseconds of the next frame to arrive, from capture start. */
    val currentOffsetMillis: Long get() = elapsedNanos / NANOS_PER_MILLI

    /** Returns every transition this frame triggered, in timeline order. */
    fun process(frame: AudioFrame): List<VadTransition> {
        val features = AudioFrameFeatures.of(frame)
        val offsetMillis = currentOffsetMillis
        val transitions = mutableListOf<VadTransition>()
        val onsetQualified =
            features.rms >= config.onsetRmsThreshold &&
                features.zeroCrossingRate <= config.maxZeroCrossingRate

        when (state) {
            State.SILENCE -> {
                onsetRun = if (onsetQualified) onsetRun + 1 else 0
                if (onsetRun >= config.onsetFrames) {
                    transitions += VadTransition(SpeechActivity.SPEECH_STARTED, VadReason.ONSET_RMS, offsetMillis)
                    enter(State.SPEECH)
                }
            }

            State.SPEECH -> {
                pauseRun = if (features.rms < config.hangoverRmsThreshold) pauseRun + 1 else 0
                if (pauseRun >= config.pauseFrames) {
                    transitions += VadTransition(SpeechActivity.CANDIDATE_PAUSE, VadReason.PAUSE_SILENCE, offsetMillis)
                    enter(State.PAUSE)
                }
            }

            State.PAUSE -> {
                onsetRun = if (onsetQualified) onsetRun + 1 else 0
                if (onsetRun >= config.onsetFrames) {
                    transitions += VadTransition(SpeechActivity.SPEECH_RESUMED, VadReason.RESUME_RMS, offsetMillis)
                    enter(State.SPEECH)
                }
            }
        }

        elapsedNanos += frame.sampleCount.toLong() * NANOS_PER_SECOND / frame.format.sampleRateHz
        return transitions
    }

    /** Clears all state so the machine can drive a new capture session. */
    fun reset() {
        state = State.SILENCE
        onsetRun = 0
        pauseRun = 0
        elapsedNanos = 0L
    }

    private fun enter(next: State) {
        state = next
        onsetRun = 0
        pauseRun = 0
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
