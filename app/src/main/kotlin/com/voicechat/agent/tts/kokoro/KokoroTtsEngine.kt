package com.voicechat.agent.tts.kokoro

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.EngineId
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.UtteranceId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.tts.TtsEngine
import com.voicechat.agent.tts.TtsEngineAvailability
import com.voicechat.agent.tts.TtsEngineEvent
import com.voicechat.agent.tts.TtsEngineFailureKind
import com.voicechat.agent.tts.TtsOutputRoute
import com.voicechat.agent.tts.TtsVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Suspendable model-state seam so engine lifecycle tests use no filesystem or graph. */
fun interface KokoroModelStateReader {
    /** Returns the integrity-checked state of [voice]. */
    suspend fun state(voice: KokoroVoiceArtifact): KokoroModelState
}

/** Optional route reporting, kept free of Android types. */
interface KokoroAudioRouteProvider {
    /** The current platform route as a privacy-safe kind, or `null` when unknown. */
    fun outputRoute(): TtsOutputRoute?
}

/**
 * Distilled Kokoro implementation of the TTS contract.
 *
 * The queue owns one player and synthesizes one graph-sized piece at a time. That
 * lets piece zero reach playback before later pieces are generated, as required
 * by `docs/voice-quality-and-latency.md` §Latency strategy. Model bytes are only
 * used after the store verifies them (`docs/model-runtime.md` §Model lifecycle).
 */
class KokoroTtsEngine(
    private val locale: Locale = Locale.getDefault(),
    voiceId: String? = null,
    private val preferredEngineId: String? = null,
    private val modelStateReader: KokoroModelStateReader,
    private val phonemizer: KokoroPhonemizer,
    private val sink: KokoroAudioSink,
    private val synthesizerLoader: (File) -> KokoroSynthesizer,
    private val diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TtsEngine {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queue = Channel<Request>(Channel.UNLIMITED)
    private val closeComplete = CompletableDeferred<Unit>()
    private val lock = Any()
    private val initializationMutex = Mutex()
    private val utteranceMutex = Mutex()
    private val playbackGate = Mutex()
    private val requests = mutableSetOf<Request>()
    private val installedVoiceCache = mutableListOf<TtsVoice>()

    @Volatile private var closed = false

    @Volatile private var ready = false

    @Volatile private var selectedVoiceId: String? = voiceId

    @Volatile private var loadedVoiceId: String? = null

    @Volatile private var synthesizer: KokoroSynthesizer? = null
    private var activeRequest: Request? = null

    private val worker: Job =
        scope.launch {
            for (request in queue) {
                utteranceMutex.withLock { process(request) }
            }
        }

    override val engineId: EngineId = ENGINE_ID

    override suspend fun initialize(): TtsEngineAvailability =
        initializationMutex.withLock {
            if (closed) return@withLock unavailable("Kokoro TTS engine is closed")
            val states =
                try {
                    withContext(Dispatchers.IO) {
                        mutableMapOf<KokoroVoiceArtifact, KokoroModelState>().also { voiceStates ->
                            for (voice in KokoroArtifact.VOICES) {
                                voiceStates[voice] = modelStateReader.state(voice)
                            }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    synchronized(lock) { installedVoiceCache.clear() }
                    clearLoadedGraph()
                    return@withLock unavailable("Kokoro voice availability could not be checked")
                }
            refreshInstalledVoices(states)

            if (preferredEngineId != null && preferredEngineId != ENGINE_ID.value) {
                clearLoadedGraph()
                return@withLock TtsEngineAvailability.SelectedEngineUnavailable(preferredEngineId)
            }

            val localeVoices = KokoroArtifact.VOICES.filter { supportsLocale(it, locale) }
            if (localeVoices.isEmpty()) {
                clearLoadedGraph()
                return@withLock TtsEngineAvailability.NoOnDeviceVoice(locale)
            }

            val requestedId = selectedVoiceId
            val selected =
                if (requestedId == null) {
                    KokoroArtifact.DEFAULT.takeIf { it in localeVoices } ?: localeVoices.first()
                } else {
                    KokoroArtifact.voice(requestedId)
                        ?: return@withLock TtsEngineAvailability.SelectedVoiceUnavailable(requestedId, locale)
                }
            if (selected !in localeVoices) {
                return@withLock if (requestedId == null) {
                    TtsEngineAvailability.NoOnDeviceVoice(locale)
                } else {
                    TtsEngineAvailability.SelectedVoiceUnavailable(requestedId, locale)
                }
            }

            when (val state = states[selected]) {
                is KokoroModelState.Installed -> {
                    try {
                        loadVerifiedGraph(selected, state.file)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        clearLoadedGraph()
                        unavailable("Kokoro voice graph could not be loaded")
                    }
                }

                KokoroModelState.Missing -> {
                    clearLoadedGraph()
                    if (requestedId != null) {
                        TtsEngineAvailability.SelectedVoiceUnavailable(requestedId, locale)
                    } else {
                        TtsEngineAvailability.NoOnDeviceVoice(locale)
                    }
                }

                is KokoroModelState.Corrupt -> {
                    clearLoadedGraph()
                    TtsEngineAvailability.Unavailable(state.error)
                }

                null -> {
                    unavailable("Kokoro voice state was not returned")
                }
            }
        }

    override fun installedVoices(): List<TtsVoice> = synchronized(lock) { installedVoiceCache.toList() }

    override fun installedEngines() = emptyList<com.voicechat.agent.tts.TtsEngineInfo>()

    override suspend fun selectVoice(voice: TtsVoice) {
        if (closed) return
        if (preferredEngineId != null && preferredEngineId != ENGINE_ID.value) return
        val artifact = KokoroArtifact.voice(voice.id) ?: return
        if (!supportsLocale(artifact, locale)) return

        val state =
            try {
                withContext(Dispatchers.IO) { modelStateReader.state(artifact) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return
            }
        if (state !is KokoroModelState.Installed) return

        if (closed) return
        loadVerifiedGraph(artifact, state.file)
    }

    override fun speak(
        text: String,
        utteranceId: UtteranceId,
    ): Flow<TtsEngineEvent> =
        flow {
            val request = Request(text, utteranceId)
            synchronized(lock) {
                when {
                    closed -> {
                        request.finishLocked(TtsEngineEvent.Failed(utteranceId, TtsEngineFailureKind.SYNTHESIS_FAILED))
                    }

                    !ready || synthesizer == null -> {
                        request.finishLocked(TtsEngineEvent.Failed(utteranceId, TtsEngineFailureKind.SYNTHESIS_FAILED))
                    }

                    else -> {
                        requests += request
                        val result = queue.trySend(request)
                        if (result.isFailure) {
                            requests.remove(request)
                            request.finishLocked(TtsEngineEvent.Failed(utteranceId, TtsEngineFailureKind.SYNTHESIS_FAILED))
                        }
                    }
                }
            }

            try {
                for (event in request.events) emit(event)
            } finally {
                if (!request.isTerminal()) cancelRequest(request)
                synchronized(lock) { requests.remove(request) }
            }
        }

    override suspend fun stop() {
        withContext(NonCancellable) {
            val playing =
                playbackGate.withLock {
                    val activeForStop =
                        synchronized(lock) {
                            val active = activeRequest
                            requests.toList().forEach { request ->
                                request.stopRequested = true
                                if (request !== active || request.state != RequestState.PLAYING) {
                                    request.finishLocked(TtsEngineEvent.Interrupted(request.utteranceId, request.deliveredText()))
                                }
                            }
                            active?.takeIf { !it.isTerminal() && it.state == RequestState.PLAYING }
                        }

                    try {
                        sink.stop()
                    } catch (_: Throwable) {
                        // Stop still terminates every request rather than stranding a queued flow.
                    }
                    activeForStop
                }
            if (playing != null) {
                val playbackFinished = synchronized(lock) { playing.playbackFinished }
                runCatching { playbackFinished?.await() }
                synchronized(lock) {
                    playing.finishLocked(TtsEngineEvent.Interrupted(playing.utteranceId, playing.deliveredText()))
                }
            }
        }
    }

    override suspend fun close() {
        val ownsClose =
            synchronized(lock) {
                if (closed) {
                    false
                } else {
                    closed = true
                    true
                }
            }
        if (!ownsClose) {
            closeComplete.await()
            return
        }
        withContext(NonCancellable) {
            try {
                stop()
                queue.close()
                worker.cancelAndJoin()
                utteranceMutex.withLock {
                    withContext(Dispatchers.IO) {
                        runCatching { sink.close() }
                        val current = synthesizer
                        synthesizer = null
                        loadedVoiceId = null
                        ready = false
                        runCatching { current?.close() }
                    }
                }
                scope.coroutineContext[Job]?.cancel()
            } finally {
                closeComplete.complete(Unit)
            }
        }
    }

    private suspend fun process(request: Request) {
        synchronized(lock) {
            if (request.isTerminal() || request.cancelled) return
            request.state = RequestState.SYNTHESIZING
            activeRequest = request
        }

        try {
            val current = synthesizer
            if (current == null || !ready) {
                request.finish(TtsEngineEvent.Failed(request.utteranceId, TtsEngineFailureKind.SYNTHESIS_FAILED))
                return
            }
            val pieces = KokoroSynthesisPipeline(phonemizer, current).pieces(request.text).iterator()
            while (pieces.hasNext()) {
                if (request.shouldStop()) {
                    request.finish(TtsEngineEvent.Interrupted(request.utteranceId, request.deliveredText()))
                    return
                }
                val piece =
                    try {
                        withContext(Dispatchers.IO) { pieces.next() }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        request.finish(TtsEngineEvent.Failed(request.utteranceId, TtsEngineFailureKind.SYNTHESIS_FAILED))
                        return
                    }

                if (piece.samples.isEmpty()) {
                    request.finish(TtsEngineEvent.Failed(request.utteranceId, TtsEngineFailureKind.SYNTHESIS_FAILED))
                    return
                }

                if (!request.beginPlayback()) {
                    request.finish(TtsEngineEvent.Interrupted(request.utteranceId, request.deliveredText()))
                    return
                }
                val played =
                    try {
                        playPiece(request, piece.samples)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        val event =
                            if (request.shouldStop()) {
                                TtsEngineEvent.Interrupted(request.utteranceId, request.deliveredText())
                            } else {
                                TtsEngineEvent.Failed(request.utteranceId, TtsEngineFailureKind.PLAYBACK_FAILED)
                            }
                        request.finish(event)
                        return
                    }

                if (played == null) {
                    request.abortPlayback()
                    request.finish(TtsEngineEvent.Interrupted(request.utteranceId, request.deliveredText()))
                    return
                }
                if (!request.finishPlayback(piece.sourceText, played)) {
                    request.finish(TtsEngineEvent.Interrupted(request.utteranceId, request.deliveredText()))
                    return
                }
            }
            request.finish(TtsEngineEvent.Completed(request.utteranceId))
        } catch (cancellation: CancellationException) {
            if (!request.isTerminal()) {
                request.finish(TtsEngineEvent.Interrupted(request.utteranceId, request.deliveredText()))
            }
            throw cancellation
        } catch (_: Throwable) {
            request.finish(TtsEngineEvent.Failed(request.utteranceId, TtsEngineFailureKind.SYNTHESIS_FAILED))
        } finally {
            synchronized(lock) {
                if (activeRequest === request) activeRequest = null
                if (request.state != RequestState.TERMINAL) request.state = RequestState.TERMINAL
            }
        }
    }

    private fun currentRoute(): TtsOutputRoute? = runCatching { (sink as? KokoroAudioRouteProvider)?.outputRoute() }.getOrNull()

    private suspend fun playPiece(
        request: Request,
        samples: FloatArray,
    ): Boolean? {
        val playback =
            playbackGate.withLock {
                if (request.shouldStop()) {
                    null
                } else {
                    scope.async(start = CoroutineStart.UNDISPATCHED) {
                        sink.play(samples) { request.markAudible(currentRoute()) }
                    }
                }
            } ?: return null
        return playback.await()
    }

    private suspend fun cancelRequest(request: Request) {
        val shouldStop =
            synchronized(lock) {
                if (request.isTerminal()) return
                request.cancelled = true
                request.finishSilentlyLocked()
                activeRequest === request && request.state == RequestState.PLAYING
            }
        if (shouldStop) {
            withContext(NonCancellable) {
                playbackGate.withLock { runCatching { sink.stop() } }
            }
        }
    }

    private suspend fun loadVerifiedGraph(
        voice: KokoroVoiceArtifact,
        file: File,
    ): TtsEngineAvailability.Ready =
        utteranceMutex.withLock {
            if (closed) throw IllegalStateException("Kokoro TTS engine is closed")
            if (loadedVoiceId == voice.id && synthesizer != null) {
                val voiceValue = voice.toTtsVoice()
                selectedVoiceId = voice.id
                ready = true
                return@withLock TtsEngineAvailability.Ready(voiceValue)
            }

            val startedAt = clock.nanoTime()
            val loaded =
                try {
                    withContext(Dispatchers.IO) { synthesizerLoader(file) }
                } catch (cancellation: CancellationException) {
                    recordGraphLoad(clock.nanoTime() - startedAt, DiagnosticOutcome.CANCELLED, voice.id)
                    throw cancellation
                } catch (failure: Throwable) {
                    recordGraphLoad(clock.nanoTime() - startedAt, DiagnosticOutcome.FAILED, voice.id)
                    throw failure
                }
            recordGraphLoad(clock.nanoTime() - startedAt, DiagnosticOutcome.COMPLETED, voice.id)
            val previous = synthesizer
            synthesizer = loaded
            loadedVoiceId = voice.id
            selectedVoiceId = voice.id
            ready = true
            synchronized(lock) {
                if (installedVoiceCache.none { it.id == voice.id }) installedVoiceCache += voice.toTtsVoice()
            }
            withContext(Dispatchers.IO) { runCatching { previous?.close() } }
            TtsEngineAvailability.Ready(voice.toTtsVoice())
        }

    private suspend fun clearLoadedGraph() {
        utteranceMutex.withLock {
            val previous = synthesizer
            synthesizer = null
            loadedVoiceId = null
            ready = false
            withContext(Dispatchers.IO) { runCatching { previous?.close() } }
        }
    }

    private fun refreshInstalledVoices(states: Map<KokoroVoiceArtifact, KokoroModelState>) {
        val installed =
            states.mapNotNull { (artifact, state) ->
                if (state is KokoroModelState.Installed) artifact.toTtsVoice() else null
            }
        synchronized(lock) {
            installedVoiceCache.clear()
            installedVoiceCache.addAll(installed)
        }
    }

    private fun recordGraphLoad(
        durationNanos: Long,
        outcome: DiagnosticOutcome,
        voiceId: String,
    ) {
        runCatching {
            diagnostics.record(
                DiagnosticEvent(
                    stage = DiagnosticStage.TTS_SYNTHESIS,
                    outcome = outcome,
                    monotonicTimeNanos = clock.nanoTime(),
                    durationNanos = durationNanos.coerceAtLeast(0L),
                    attributes =
                        mapOf(
                            DiagnosticAttribute.ENGINE_ID to ENGINE_ID.value,
                            DiagnosticAttribute.MODEL_ID to voiceId,
                        ),
                ),
            )
        }
    }

    private fun unavailable(detail: String): TtsEngineAvailability.Unavailable =
        TtsEngineAvailability.Unavailable(VoiceAgentError(ErrorCode.TTS_SYNTHESIS_FAILED, detail))

    private fun supportsLocale(
        voice: KokoroVoiceArtifact,
        requested: Locale,
    ): Boolean = requested.language.isNotBlank() && Locale.forLanguageTag(voice.languageTag).language == requested.language

    private fun KokoroVoiceArtifact.toTtsVoice(): TtsVoice =
        TtsVoice(
            id = id,
            displayName = displayName,
            locale = Locale.forLanguageTag(languageTag),
            requiresNetwork = false,
            quality = DISTILLED_QUALITY_RANK,
            latency = ON_DEVICE_LATENCY_RANK,
        )

    private enum class RequestState {
        QUEUED,
        SYNTHESIZING,
        PLAYING,
        TERMINAL,
    }

    private inner class Request(
        val text: String,
        val utteranceId: UtteranceId,
    ) {
        val events = Channel<TtsEngineEvent>(Channel.UNLIMITED)
        private val delivered = StringBuilder()
        private var started = false
        private var terminal = false
        var state: RequestState = RequestState.QUEUED
        var cancelled = false
        var stopRequested = false
        var playbackFinished: CompletableDeferred<Unit>? = null

        fun isTerminal(): Boolean = synchronized(lock) { terminal }

        fun deliveredText(): String = synchronized(lock) { delivered.toString() }

        fun shouldStop(): Boolean = synchronized(lock) { terminal || cancelled || stopRequested }

        fun beginPlayback(): Boolean =
            synchronized(lock) {
                if (terminal || cancelled || stopRequested) {
                    false
                } else {
                    state = RequestState.PLAYING
                    playbackFinished = CompletableDeferred()
                    true
                }
            }

        fun markAudible(route: TtsOutputRoute?) {
            synchronized(lock) {
                if (terminal || cancelled || started) return
                started = true
                events.trySend(TtsEngineEvent.Started(utteranceId, route))
            }
        }

        fun finishPlayback(
            sourceText: String,
            played: Boolean,
        ): Boolean =
            synchronized(lock) {
                if (played && !terminal && !cancelled) delivered.append(sourceText)
                state = RequestState.SYNTHESIZING
                playbackFinished?.complete(Unit)
                playbackFinished = null
                !terminal && !cancelled && !stopRequested && played
            }

        fun abortPlayback() {
            synchronized(lock) {
                state = RequestState.SYNTHESIZING
                playbackFinished?.complete(Unit)
                playbackFinished = null
            }
        }

        fun finish(event: TtsEngineEvent) {
            synchronized(lock) { finishLocked(event) }
        }

        fun finishLocked(event: TtsEngineEvent) {
            if (terminal) return
            terminal = true
            state = RequestState.TERMINAL
            events.trySend(event)
            events.close()
            playbackFinished?.complete(Unit)
        }

        fun finishSilentlyLocked() {
            if (terminal) return
            terminal = true
            state = RequestState.TERMINAL
            events.close()
            playbackFinished?.complete(Unit)
        }
    }

    companion object {
        /** Stable engine identity used by settings and diagnostics. */
        val ENGINE_ID: EngineId = EngineId("kokoro-distilled")

        /** Naturalness rank for the locally distilled voice; rank is not a quality claim. */
        const val DISTILLED_QUALITY_RANK: Int = 5

        /** Relative low-latency rank for on-device graph inference. */
        const val ON_DEVICE_LATENCY_RANK: Int = 1
    }
}
