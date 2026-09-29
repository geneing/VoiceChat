package com.voicechat.agent.replay

import com.voicechat.agent.contracts.SpeechToText
import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.contracts.TurnCompletion
import com.voicechat.agent.contracts.TurnCompletionDetector
import com.voicechat.agent.contracts.VadEvent
import com.voicechat.agent.contracts.VoiceActivityDetector
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.EngineId
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TranscriptRevision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Replays a fixture's labeled transcript hypotheses through the [SpeechToText]
 * contract while consuming the replayed audio.
 *
 * The audio is not interpreted; it drives *when* each label is emitted so
 * partial revisions and the final result arrive on the same timeline a real
 * recognizer would produce. Labels scheduled at or before the current frame are
 * emitted as the frames arrive; any remaining labels are flushed after the audio
 * completes. Everything is deterministic for a given fixture.
 */
class ReplaySpeechToText(
    fixture: PcmFixture,
) : SpeechToText {
    private val format = fixture.manifest.format
    private val labels: List<TranscriptLabel> =
        fixture.manifest.labels.transcriptRevisions
            .sortedBy { it.frameIndex }

    override val engineId: EngineId = EngineId(fixture.manifest.engine.engineId)

    /** Number of audio frames observed across all sessions. */
    var observedFrameCount: Int = 0
        private set

    /** True once [close] has been called. */
    var closed: Boolean = false
        private set

    override fun transcribe(audio: Flow<AudioFrame>): Flow<SttEvent> =
        flow {
            var pointer = 0
            var frameIndex = 0
            audio.collect { frame ->
                observedFrameCount++
                check(frame.format == format) {
                    "replayed frame format ${frame.format} does not match fixture $format"
                }
                while (pointer < labels.size && labels[pointer].frameIndex <= frameIndex) {
                    emit(labels[pointer].toResult(pointer))
                    pointer++
                }
                frameIndex++
            }
            while (pointer < labels.size) {
                emit(labels[pointer].toResult(pointer))
                pointer++
            }
        }

    override suspend fun close() {
        closed = true
    }

    private fun TranscriptLabel.toResult(revision: Int): SttEvent.Result {
        val transcript =
            if (isFinal) {
                Transcript.final(text, TranscriptRevision(revision))
            } else {
                Transcript.interim(text, TranscriptRevision(revision))
            }
        return SttEvent.Result(transcript)
    }
}

/**
 * Replays a fixture's labeled speech-activity transitions through the
 * [VoiceActivityDetector] contract.
 *
 * Offsets are derived from the labeled frame index and the fixture frame size,
 * so they stay tied to the audio timeline. Onset, candidate pause, and resumed
 * speech are emitted in fixture order.
 */
class ReplayVoiceActivityDetector(
    private val fixture: PcmFixture,
) : VoiceActivityDetector {
    private val labels: List<VadLabel> =
        fixture.manifest.labels.vadEvents
            .sortedBy { it.frameIndex }

    override fun observe(audio: Flow<AudioFrame>): Flow<VadEvent> =
        flow {
            var pointer = 0
            var frameIndex = 0
            audio.collect {
                while (pointer < labels.size && labels[pointer].frameIndex <= frameIndex) {
                    emit(labels[pointer].toEvent())
                    pointer++
                }
                frameIndex++
            }
            while (pointer < labels.size) {
                emit(labels[pointer].toEvent())
                pointer++
            }
        }

    private fun VadLabel.toEvent(): VadEvent.Activity =
        VadEvent.Activity(
            activity = activity,
            atOffsetMillis = frameOffsetMillis(frameIndex, fixture.manifest.frameSizeSamples, fixture.format.sampleRateHz),
        )
}

/**
 * Replays a fixture's semantic end-of-turn decisions in call order.
 *
 * Once the scripted decisions are exhausted it reports [TurnCompletion.UNAVAILABLE]
 * rather than guessing, matching the contract's requirement that a missing or
 * unusable model is explicit.
 */
class ReplayTurnCompletionDetector(
    fixture: PcmFixture,
) : TurnCompletionDetector {
    private val decisions: List<TurnCompletion> = fixture.manifest.labels.turnCompletions

    /** Number of [evaluate] calls. */
    var evaluationCount: Int = 0
        private set

    /** Windows passed to [evaluate], for assertions on what was classified. */
    val evaluatedWindows: MutableList<AudioFrame> = mutableListOf()

    /** True once [close] has been called. */
    var closed: Boolean = false
        private set

    override suspend fun evaluate(window: AudioFrame): TurnCompletion {
        val decision = decisions.getOrElse(evaluationCount) { TurnCompletion.UNAVAILABLE }
        evaluationCount++
        evaluatedWindows += window
        return decision
    }

    override suspend fun close() {
        closed = true
    }
}

/** Monotonic offset of a frame boundary, in milliseconds. */
internal fun frameOffsetMillis(
    frameIndex: Int,
    frameSizeSamples: Int,
    sampleRateHz: Int,
): Long = frameIndex.toLong() * frameSizeSamples * 1000L / sampleRateHz
