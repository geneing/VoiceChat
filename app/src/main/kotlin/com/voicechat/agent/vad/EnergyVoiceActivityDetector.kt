package com.voicechat.agent.vad

import com.voicechat.agent.audio.AudioRouteType
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.VadEvent
import com.voicechat.agent.contracts.VoiceActivityDetector
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.VoiceAgentError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile

/**
 * The fast, measured-audio [VoiceActivityDetector] used for speech onset.
 *
 * This is the low-latency activity path: it emits `SPEECH_STARTED`,
 * `CANDIDATE_PAUSE`, and `SPEECH_RESUMED` from frame RMS and zero-crossing
 * measurements ([VadStateMachine]) with an onset latency of roughly
 * `onsetFrames` × frame duration (~60 ms by default). Because it never waits for
 * a semantic model, it is the correct signal for barge-in: playback can be
 * stopped on `SPEECH_STARTED` without asking whether the thought is complete.
 *
 * It reports activity only. Deciding whether a candidate pause ends a turn is
 * the bounded endpoint policy's job ([BoundedTurnEndpointPolicy]), which is why
 * this class stays independent of Smart Turn or any other completion model.
 *
 * Only mono frames are accepted; a non-mono frame produces a typed
 * [VadEvent.Failed] rather than silently mis-measuring. All events are
 * deterministic for a given frame sequence.
 */
class EnergyVoiceActivityDetector(
    private val config: VadConfig = VadConfig.default(),
    sink: DiagnosticsSink = NoOpDiagnosticsSink,
    clock: MonotonicClock = SystemMonotonicClock,
    route: AudioRouteType? = null,
    traceId: TraceId? = null,
    turnId: TurnId? = null,
) : VoiceActivityDetector {
    private val diagnostics = TurnDetectionDiagnostics(sink, clock, route, traceId, turnId)

    override fun observe(audio: Flow<AudioFrame>): Flow<VadEvent> =
        flow {
            val machine = VadStateMachine(config)
            var failure: VoiceAgentError? = null

            audio
                .takeWhile { frame ->
                    if (frame.format.isMono) {
                        true
                    } else {
                        failure =
                            VoiceAgentError(
                                ErrorCode.TURN_DETECTION_FAILED,
                                "voice activity detection requires mono audio",
                            )
                        false
                    }
                }.collect { frame ->
                    val transitions = machine.process(frame)
                    if (transitions.isNotEmpty()) {
                        val rms = AudioFrameFeatures.of(frame).rms
                        transitions.forEach { transition ->
                            diagnostics.speechActivity(transition.activity, transition.reason, rms, transition.atOffsetMillis)
                            emit(VadEvent.Activity(transition.activity, transition.atOffsetMillis))
                        }
                    }
                }

            failure?.let { error ->
                diagnostics.failed(error.code)
                emit(VadEvent.Failed(error))
            }
        }

    /** The configured transition thresholds, for tests and diagnostics. */
    val configuration: VadConfig get() = config
}
