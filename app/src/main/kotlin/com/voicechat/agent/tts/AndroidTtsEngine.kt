package com.voicechat.agent.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.voicechat.agent.audio.AndroidAudioFocusController
import com.voicechat.agent.audio.AudioFocusController
import com.voicechat.agent.domain.EngineId
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.UtteranceId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.log.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * [TtsEngine] backed by the platform `android.speech.tts.TextToSpeech` API — the
 * M00-selected TTS engine (`docs/decisions.md` §2.2). All `android.speech.tts`
 * types stay in this file and [AndroidTtsOutputRoute]; the rest of the tts
 * package is platform-free.
 *
 * **On-device only.** [initialize] enumerates `getVoices()` and selects a voice
 * only when `isNetworkConnectionRequired()` is `false`. When the requested
 * locale has no embedded voice it returns [TtsEngineAvailability.NoOnDeviceVoice]
 * and the adapter surfaces it; a network voice is never used.
 *
 * **Queued chunking.** [speak] enqueues with `QUEUE_ADD`, so incremental chunks
 * play in order. Each call returns a cold flow backed by a `callbackFlow`
 * registered under its utterance id; the platform `UtteranceProgressListener`
 * routes `onStart`/`onDone`/`onError`/`onStop`/`onRangeStart` to it.
 *
 * **Immediate stop.** [stop] calls `tts.stop()` and then force-completes every
 * still-registered flow with [TtsEngineEvent.Interrupted] (the confirmed prefix
 * from `onRangeStart`, or empty), so no `speak` flow can hang if the engine
 * omits a callback for a queued utterance.
 *
 * **Focus.** The engine holds transient `USAGE_ASSISTANT` audio focus for the
 * span in which any utterance is active and abandons it when idle; the final
 * capture/TTS focus policy is M24's (R-0050).
 */
class AndroidTtsEngine(
    context: Context,
    private val locale: Locale = Locale.getDefault(),
    private val focusController: AudioFocusController = AndroidAudioFocusController(context.applicationContext),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TtsEngine {
    private val appContext = context.applicationContext

    @Volatile
    private var tts: TextToSpeech? = null

    private val initStatus = CompletableDeferred<Int>()

    @Volatile
    private var initialized = false

    @Volatile
    private var closed = false

    private val activeUtterances = AtomicInteger(0)
    private val pendingUtterances = ConcurrentHashMap<String, PendingUtterance>()

    init {
        tts =
            runCatching {
                TextToSpeech(appContext) { status -> initStatus.complete(status) }
            }.onFailure { AppLog.w(it) { "tts: TextToSpeech construction failed" } }
                .getOrNull()
    }

    override val engineId: EngineId
        get() = EngineId(tts?.defaultEngine?.takeIf { it.isNotBlank() } ?: FALLBACK_ENGINE_ID)

    override suspend fun initialize(): TtsEngineAvailability =
        withContext(dispatcher) {
            val textToSpeech =
                tts ?: return@withContext TtsEngineAvailability.Unavailable(
                    VoiceAgentError(ErrorCode.TTS_SYNTHESIS_FAILED, "text-to-speech engine unavailable"),
                )
            if (!initialized) {
                val status = withTimeoutOrNull(INIT_TIMEOUT_MILLIS) { initStatus.await() }
                if (status != TextToSpeech.SUCCESS) {
                    AppLog.w { "tts: engine init failed status=$status engine=${engineId.value}" }
                    return@withContext TtsEngineAvailability.Unavailable(
                        VoiceAgentError(ErrorCode.TTS_SYNTHESIS_FAILED, "text-to-speech engine failed to initialize"),
                    )
                }
                textToSpeech.setOnUtteranceProgressListener(progressListener)
                initialized = true
            }

            val voices = installedVoices()
            val selected = OnDeviceVoiceSelector.select(voices, locale)
            AppLog.i {
                "tts: availability engine=${engineId.value} voices=${voices.size} " +
                    "onDevice=${OnDeviceVoiceSelector.onDeviceVoices(voices).size} selected=${selected?.id ?: "none"}"
            }
            if (selected == null) {
                TtsEngineAvailability.NoOnDeviceVoice(locale)
            } else {
                selectVoice(selected)
                TtsEngineAvailability.Ready(selected)
            }
        }

    override fun installedVoices(): List<TtsVoice> {
        val platformVoices = tts?.voices ?: return emptyList()
        return platformVoices.mapNotNull { voice ->
            val name = voice.name ?: return@mapNotNull null
            TtsVoice(
                id = name,
                displayName = name,
                locale = voice.locale,
                requiresNetwork = voice.isNetworkConnectionRequired,
                quality = voice.quality,
                latency = voice.latency,
            )
        }
    }

    override suspend fun selectVoice(voice: TtsVoice) {
        val textToSpeech = tts ?: return
        val platformVoice = textToSpeech.voices?.firstOrNull { it.name == voice.id } ?: return
        runCatching { textToSpeech.voice = platformVoice }
            .onFailure { AppLog.w(it) { "tts: setVoice failed voice=${voice.id}" } }
    }

    override fun speak(
        text: String,
        utteranceId: UtteranceId,
    ): Flow<TtsEngineEvent> =
        callbackFlow {
            val id = utteranceId.value
            if (closed || !initialized) {
                trySend(TtsEngineEvent.Failed(utteranceId, TtsEngineFailureKind.SYNTHESIS_FAILED))
                close()
                awaitClose { }
                return@callbackFlow
            }

            val pending = PendingUtterance(text, this)
            pendingUtterances[id] = pending
            if (activeUtterances.incrementAndGet() == 1) focusController.acquire()

            val result =
                runCatching {
                    tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id) ?: TextToSpeech.ERROR
                }.getOrDefault(TextToSpeech.ERROR)
            if (result != TextToSpeech.SUCCESS) {
                AppLog.w { "tts: speak rejected chars=${text.length}" }
                dispatch(id, TtsEngineEvent.Failed(utteranceId, TtsEngineFailureKind.SYNTHESIS_FAILED))
            }

            awaitClose {
                pendingUtterances.remove(id)
                releaseFocusIfIdle()
            }
        }

    override suspend fun stop() {
        if (closed) return
        AppLog.d { "tts: stop pending=${pendingUtterances.size}" }
        runCatching { tts?.stop() }
        pendingUtterances.keys.toList().forEach { id ->
            val pending = pendingUtterances.remove(id) ?: return@forEach
            dispatchInterrupted(id, pending)
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        runCatching { tts?.stop() }
        pendingUtterances.values.forEach { it.channel.close() }
        pendingUtterances.clear()
        runCatching { tts?.shutdown() }
        focusController.abandon()
    }

    private fun dispatch(
        id: String,
        event: TtsEngineEvent,
    ) {
        val pending = pendingUtterances[id] ?: return
        pending.channel.trySend(event)
        if (event is TtsEngineEvent.Completed ||
            event is TtsEngineEvent.Interrupted ||
            event is TtsEngineEvent.Failed
        ) {
            pendingUtterances.remove(id)
            pending.channel.close()
        }
    }

    private fun dispatchInterrupted(
        id: String,
        pending: PendingUtterance,
    ) {
        pending.channel.trySend(TtsEngineEvent.Interrupted(UtteranceId(id), pending.deliveredPrefix()))
        pending.channel.close()
    }

    private fun releaseFocusIfIdle() {
        if (activeUtterances.updateAndGet { current -> if (current > 0) current - 1 else 0 } == 0) {
            focusController.abandon()
        }
    }

    private val progressListener: UtteranceProgressListener =
        object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val id = utteranceId ?: return
                dispatch(id, TtsEngineEvent.Started(UtteranceId(id), AndroidTtsOutputRoute.current(appContext)))
            }

            override fun onDone(utteranceId: String?) {
                val id = utteranceId ?: return
                dispatch(id, TtsEngineEvent.Completed(UtteranceId(id)))
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                val id = utteranceId ?: return
                AppLog.w { "tts: engine error (legacy)" }
                dispatch(id, TtsEngineEvent.Failed(UtteranceId(id), TtsEngineFailureKind.PLAYBACK_FAILED))
            }

            override fun onError(
                utteranceId: String?,
                errorCode: Int,
            ) {
                val id = utteranceId ?: return
                val kind =
                    if (errorCode == TextToSpeech.ERROR_SYNTHESIS) {
                        TtsEngineFailureKind.SYNTHESIS_FAILED
                    } else {
                        TtsEngineFailureKind.PLAYBACK_FAILED
                    }
                AppLog.w { "tts: engine error code=$errorCode kind=${kind.name}" }
                dispatch(id, TtsEngineEvent.Failed(UtteranceId(id), kind))
            }

            override fun onStop(
                utteranceId: String?,
                interrupted: Boolean,
            ) {
                val id = utteranceId ?: return
                val pending = pendingUtterances[id] ?: return
                dispatchInterrupted(id, pending)
            }

            override fun onRangeStart(
                utteranceId: String?,
                start: Int,
                end: Int,
                frame: Int,
            ) {
                val id = utteranceId ?: return
                pendingUtterances[id]?.recordDeliveredPrefix(start)
            }
        }

    private class PendingUtterance(
        val text: String,
        val channel: SendChannel<TtsEngineEvent>,
    ) {
        private val deliveredPrefixLength = AtomicInteger(0)

        fun recordDeliveredPrefix(start: Int) {
            val bounded = start.coerceIn(0, text.length)
            deliveredPrefixLength.updateAndGet { current -> maxOf(current, bounded) }
        }

        fun deliveredPrefix(): String = text.take(deliveredPrefixLength.get())
    }

    private companion object {
        const val FALLBACK_ENGINE_ID = "android.speech.tts"
        const val INIT_TIMEOUT_MILLIS = 5_000L
    }
}
