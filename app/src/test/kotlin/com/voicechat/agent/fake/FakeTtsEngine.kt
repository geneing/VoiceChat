package com.voicechat.agent.fake

import com.voicechat.agent.domain.EngineId
import com.voicechat.agent.domain.UtteranceId
import com.voicechat.agent.tts.OnDeviceVoiceSelector
import com.voicechat.agent.tts.TtsEngine
import com.voicechat.agent.tts.TtsEngineAvailability
import com.voicechat.agent.tts.TtsEngineEvent
import com.voicechat.agent.tts.TtsEngineFailureKind
import com.voicechat.agent.tts.TtsVoice
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Locale

/** Builds a [TtsVoice] for tests without repeating the constructor. */
fun ttsVoice(
    id: String,
    language: String = "en",
    country: String = "US",
    requiresNetwork: Boolean = false,
    quality: Int = 0,
    latency: Int = 0,
): TtsVoice =
    TtsVoice(
        id = id,
        displayName = id,
        locale = Locale.forLanguageTag("$language-$country"),
        requiresNetwork = requiresNetwork,
        quality = quality,
        latency = latency,
    )

/**
 * Deterministic [TtsEngine] for JVM tests — no Android, no device.
 *
 * With the default [autoComplete] the fake reports `Started` then `Completed`.
 * With `autoComplete = false` it reports `Started` and then waits: [stop]
 * interrupts every waiting utterance with an empty delivered prefix, which
 * exercises the immediate-stop accounting path. [failFirst] makes the first
 * `speak` fail with a synthesis error.
 */
class FakeTtsEngine(
    private val voices: List<TtsVoice> = listOf(ttsVoice("voice-1")),
    private val autoComplete: Boolean = true,
    private val failFirst: Boolean = false,
    private val locale: Locale = Locale.US,
    private val preferredVoiceId: String? = null,
) : TtsEngine {
    override val engineId: EngineId = EngineId("fake-tts")

    /** Chunk texts passed to [speak], in call order. */
    val spokenTexts: MutableList<String> = mutableListOf()

    /** Number of [stop] calls. */
    var stopCount: Int = 0
        private set

    /** Number of [close] calls. */
    var closeCount: Int = 0
        private set

    /** The voice reported as selected by [initialize]/[selectVoice]. */
    var selectedVoice: TtsVoice? = null
        private set

    private val channels = LinkedHashMap<UtteranceId, Channel<TtsEngineEvent>>()
    private var speakCount = 0

    override suspend fun initialize(): TtsEngineAvailability {
        if (preferredVoiceId != null) {
            val preferred = OnDeviceVoiceSelector.selectPreferred(voices, preferredVoiceId)
            selectedVoice = preferred
            return preferred?.let { TtsEngineAvailability.Ready(it) }
                ?: TtsEngineAvailability.SelectedVoiceUnavailable(preferredVoiceId, locale)
        }
        val selected = OnDeviceVoiceSelector.select(voices, locale)
        selectedVoice = selected
        return selected?.let { TtsEngineAvailability.Ready(it) }
            ?: TtsEngineAvailability.NoOnDeviceVoice(locale)
    }

    override fun installedVoices(): List<TtsVoice> = voices

    override suspend fun selectVoice(voice: TtsVoice) {
        selectedVoice = voice
    }

    override fun speak(
        text: String,
        utteranceId: UtteranceId,
    ): Flow<TtsEngineEvent> =
        flow {
            spokenTexts += text
            speakCount++
            if (failFirst && speakCount == 1) {
                emit(TtsEngineEvent.Failed(utteranceId, TtsEngineFailureKind.SYNTHESIS_FAILED))
                return@flow
            }
            if (autoComplete) {
                emit(TtsEngineEvent.Started(utteranceId))
                emit(TtsEngineEvent.Completed(utteranceId))
            } else {
                val channel = Channel<TtsEngineEvent>(Channel.UNLIMITED)
                channels[utteranceId] = channel
                emit(TtsEngineEvent.Started(utteranceId))
                try {
                    for (event in channel) emit(event)
                } finally {
                    channels.remove(utteranceId)
                }
            }
        }

    override suspend fun stop() {
        stopCount++
        channels.keys.toList().forEach { id ->
            val channel = channels.remove(id) ?: return@forEach
            channel.trySend(TtsEngineEvent.Interrupted(id, ""))
            channel.close()
        }
    }

    override suspend fun close() {
        closeCount++
        channels.values.forEach { it.close() }
        channels.clear()
    }
}
