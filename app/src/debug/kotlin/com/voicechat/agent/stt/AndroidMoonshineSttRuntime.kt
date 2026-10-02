package com.voicechat.agent.stt

import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriptEvent
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Moonshine Android SDK boundary; this file exists only in the debug variant. */
internal class AndroidMoonshineSttRuntime(
    context: Context,
    private val model: MoonshineSttModel,
) : MoonshineSttRuntime {
    private val appContext = context.applicationContext
    private val modelRoot = File(File(appContext.filesDir, "moonshine"), model.modelId.value)
    private val loadMutex = Mutex()
    private var transcriber: Transcriber? = null

    @Volatile private var closed = false

    override suspend fun openSession(
        updateIntervalSeconds: Double,
        onResponse: (SttEngineResponse) -> Unit,
    ): MoonshineSttSession =
        withContext(Dispatchers.IO) {
            loadMutex.withLock {
                check(!closed) { "Moonshine runtime has been closed" }
                val engine = transcriber ?: loadTranscriber().also { transcriber = it }
                engine.setUpdateInterval(updateIntervalSeconds)

                val streamHandle = engine.createStream()
                val listener: (TranscriptEvent) -> Unit = { event ->
                    when (event) {
                        is TranscriptEvent.LineTextChanged -> {
                            if (event.streamHandle == streamHandle && !event.line.isComplete) {
                                onResponse(SttEngineResponse.Partial(event.line.text.orEmpty()))
                            }
                        }

                        is TranscriptEvent.LineCompleted -> {
                            if (event.streamHandle == streamHandle) {
                                onResponse(SttEngineResponse.Final(event.line.text.orEmpty()))
                            }
                        }

                        is TranscriptEvent.Error -> {
                            if (event.streamHandle == streamHandle) {
                                onResponse(
                                    SttEngineResponse.Failure(
                                        SttFailureKind.RECOGNITION_FAILED,
                                        detail = "Moonshine emitted an STT error",
                                    ),
                                )
                            }
                        }

                        else -> {
                            Unit
                        }
                    }
                }
                engine.addListener(listener)
                try {
                    engine.startStream(streamHandle)
                } catch (failure: Exception) {
                    engine.removeListener(listener)
                    engine.freeStream(streamHandle)
                    throw failure
                }

                AndroidMoonshineSttSession(engine, streamHandle, listener)
            }
        }

    override fun close() {
        closed = true
        val loaded = transcriber
        transcriber = null
        loaded?.close()
    }

    private fun loadTranscriber(): Transcriber {
        val spec = ModelSpec.stt("en", model.architecture, false)
        val modelDirectory =
            AssetDownloader().ensureModelPresent(
                modelRoot,
                spec,
                null,
            )
        return Transcriber().also { engine ->
            try {
                engine.loadFromFiles(modelDirectory.absolutePath, model.architecture)
            } catch (failure: Exception) {
                engine.close()
                throw failure
            }
        }
    }
}

private class AndroidMoonshineSttSession(
    private val transcriber: Transcriber,
    private val streamHandle: Int,
    private val listener: (TranscriptEvent) -> Unit,
) : MoonshineSttSession {
    private val closed = AtomicBoolean(false)
    private var stopped = false

    override fun addAudio(
        samples: FloatArray,
        sampleRateHz: Int,
    ) {
        check(!closed.get()) { "Moonshine stream has been released" }
        transcriber.addAudioToStream(streamHandle, samples, sampleRateHz)
    }

    override fun stopAndFlush() {
        if (closed.get() || stopped) return
        transcriber.stopStream(streamHandle)
        stopped = true
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            transcriber.removeListener(listener)
            transcriber.freeStream(streamHandle)
        }
    }
}
