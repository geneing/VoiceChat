package com.voicechat.agent.fake

import android.media.AudioRecord
import com.voicechat.agent.audio.AudioFocusController
import com.voicechat.agent.audio.AudioFocusState
import com.voicechat.agent.audio.AudioRoute
import com.voicechat.agent.audio.AudioRouteMonitor
import com.voicechat.agent.audio.AudioRouteType
import com.voicechat.agent.audio.MicrophonePermission
import com.voicechat.agent.audio.PcmRecorderEngine
import com.voicechat.agent.audio.PcmRecorderFactory
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Deterministic [PcmRecorderEngine] for capture tests.
 *
 * It serves a fixed sample buffer in request-sized chunks. Once the buffer is
 * exhausted [read] returns 0 (like a transient empty read) until the collector
 * cancels. It records the thread of every platform call so a test can prove
 * capture never runs on the main thread.
 */
class FakePcmRecorderEngine(
    override val format: AudioFormat = AudioFormat.MONO_16_KHZ,
    samples: ShortArray = ShortArray(0),
    private val startFailure: VoiceAgentError? = null,
    private val readFailureAfterFrames: Int? = null,
    private val readFailureCode: Int = AudioRecord.ERROR_DEAD_OBJECT,
) : PcmRecorderEngine {
    private val source = samples.copyOf()
    private var cursor = 0
    private var successfulReads = 0

    var startCount: Int = 0
        private set

    var stopCount: Int = 0
        private set

    var releaseCount: Int = 0
        private set

    /** Thread name of every [read] call, in order. */
    val readThreadNames: MutableList<String> = mutableListOf()

    /** Thread name of every start/stop/release call, in order. */
    val lifecycleThreadNames: MutableList<String> = mutableListOf()

    override fun start() {
        startCount++
        lifecycleThreadNames += Thread.currentThread().name
        startFailure?.let { throw VoiceAgentException(it) }
    }

    override fun read(
        buffer: ShortArray,
        offset: Int,
        size: Int,
    ): Int {
        readThreadNames += Thread.currentThread().name
        if (readFailureAfterFrames != null && successfulReads >= readFailureAfterFrames) return readFailureCode
        if (cursor >= source.size) return 0
        val count = minOf(size, source.size - cursor)
        source.copyInto(buffer, offset, cursor, cursor + count)
        cursor += count
        successfulReads++
        return count
    }

    override fun stop() {
        stopCount++
        lifecycleThreadNames += Thread.currentThread().name
    }

    override fun release() {
        releaseCount++
        lifecycleThreadNames += Thread.currentThread().name
    }
}

/** [PcmRecorderFactory] that hands out one [FakePcmRecorderEngine]. */
class FakePcmRecorderFactory(
    private val engine: PcmRecorderEngine,
    private val createFailure: VoiceAgentException? = null,
) : PcmRecorderFactory {
    var createCount: Int = 0
        private set

    override fun create(): PcmRecorderEngine {
        createCount++
        createFailure?.let { throw it }
        return engine
    }
}

/**
 * [MicrophonePermission] whose grant state can be flipped, and which can revoke
 * itself after a number of checks to simulate mid-capture revocation.
 */
class FakeMicrophonePermission(
    @Volatile private var granted: Boolean = true,
    private val revokeAfterChecks: Int? = null,
) : MicrophonePermission {
    var checkCount: Int = 0
        private set

    override fun isGranted(): Boolean {
        checkCount++
        revokeAfterChecks?.let { if (checkCount > it) granted = false }
        return granted
    }

    fun setGranted(value: Boolean) {
        granted = value
    }
}

/** [AudioRouteMonitor] whose route can be changed on demand. */
class FakeAudioRouteMonitor(
    @Volatile private var route: AudioRoute = AudioRoute(AudioRouteType.BUILTIN_MIC),
) : AudioRouteMonitor {
    private val changes = MutableSharedFlow<AudioRoute>(replay = 1, extraBufferCapacity = 16)

    override fun current(): AudioRoute = route

    override fun routes(): Flow<AudioRoute> =
        flow {
            emit(route)
            emitAll(changes)
        }

    fun setRoute(newRoute: AudioRoute) {
        route = newRoute
        changes.tryEmit(newRoute)
    }
}

/** [AudioFocusController] recording acquire/abandon calls. */
class FakeAudioFocusController(
    private val grant: Boolean = true,
) : AudioFocusController {
    var acquireCount: Int = 0
        private set

    var abandonCount: Int = 0
        private set

    private val state = MutableStateFlow(AudioFocusState.RELEASED)

    override fun acquire(): Boolean {
        acquireCount++
        if (grant) state.value = AudioFocusState.ACQUIRED
        return grant
    }

    override fun abandon() {
        abandonCount++
        state.value = AudioFocusState.RELEASED
    }

    override fun state(): StateFlow<AudioFocusState> = state.asStateFlow()
}
