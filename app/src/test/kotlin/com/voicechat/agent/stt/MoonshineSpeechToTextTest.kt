package com.voicechat.agent.stt

import android.content.Context
import com.voicechat.agent.contracts.SpeechToText
import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.ErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Contract and lifecycle checks for the debug-only adapter, without loading JNI or model files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MoonshineSpeechToTextTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun reportsBothCandidateModelsAndPreservesPartialThenFinalSemantics() =
        runBlocking {
            val runtime = FakeRuntime()
            val speechToText: SpeechToText =
                MoonshineSpeechToText(
                    context = context,
                    model = MoonshineSttModel.SMALL_STREAMING,
                    runtime = runtime,
                )

            assertEquals("moonshine-voice", speechToText.engineId.value)
            assertEquals("moonshine-small-streaming-en", (speechToText as MoonshineSpeechToText).model.modelId.value)
            assertEquals(
                setOf("moonshine-tiny-streaming-en", "moonshine-small-streaming-en"),
                MoonshineSttModel.entries.map { it.modelId.value }.toSet(),
            )

            val events = speechToText.transcribe(flowOf(frame())).toList()
            val results = events.filterIsInstance<SttEvent.Result>()

            assertTrue("expected revisable hypotheses before finalization", results.size >= 2)
            assertTrue(
                results
                    .first()
                    .transcript.isFinal
                    .not(),
            )
            assertEquals("hello", results.first().transcript.text)
            assertTrue(results.last().transcript.isFinal)
            assertEquals("hello world", results.last().transcript.text)
            assertEquals(listOf(16_000), runtime.session.sampleRates)
            assertEquals(
                listOf(floatArrayOf(Short.MIN_VALUE / 32_768f, 0f, Short.MAX_VALUE / 32_768f).toList()),
                runtime.session.audio.map { it.toList() },
            )
            assertEquals(1, runtime.session.stopCount)

            speechToText.close()
        }

    @Test
    fun modelOpenFailureIsReportedAsATypedSttFailure() =
        runBlocking {
            val adapter =
                MoonshineSpeechToText(
                    context = context,
                    model = MoonshineSttModel.TINY_STREAMING,
                    runtime = FakeRuntime(failOpen = true),
                )

            val event = adapter.transcribe(emptyFlow()).toList().single() as SttEvent.Failed

            assertEquals(ErrorCode.STT_MODEL_NOT_READY, event.error.code)
            assertEquals("Moonshine model download or load failed", event.error.detail)
            adapter.close()
        }

    @Test
    fun collectionCancellationFreesStreamWithoutFlushingAndCloseWaitsForNativeCall() =
        runBlocking {
            val addAudioEntered = CountDownLatch(1)
            val allowAddAudioToReturn = CountDownLatch(1)
            val runtime = FakeRuntime(addAudioEntered = addAudioEntered, allowAddAudioToReturn = allowAddAudioToReturn)
            val adapter =
                MoonshineSpeechToText(
                    context = context,
                    model = MoonshineSttModel.TINY_STREAMING,
                    runtime = runtime,
                )
            val collector =
                launch(Dispatchers.IO) {
                    adapter
                        .transcribe(
                            flow {
                                emit(frame())
                                awaitCancellation()
                            },
                        ).collect { }
                }

            assertTrue(
                "fake native call did not start",
                withContext(Dispatchers.IO) { addAudioEntered.await(2, TimeUnit.SECONDS) },
            )
            val closing = async(Dispatchers.IO) { adapter.close() }
            try {
                assertFalse("close must wait until native addAudio returns", closing.awaitUnlessCompleted(100))
                assertFalse("native transcriber must remain open during addAudio", runtime.closed.get())
            } finally {
                allowAddAudioToReturn.countDown()
            }

            closing.await()
            collector.cancelAndJoin()

            assertTrue(runtime.session.released.get())
            assertEquals("stop is a flush and must not be used for cancellation", 0, runtime.session.stopCount)
            assertTrue(runtime.closed.get())
            adapter.close()
        }

    private fun frame(): AudioFrame =
        AudioFrame(
            format = AudioFormat.MONO_16_KHZ,
            samples = shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE),
        )

    private class FakeRuntime(
        private val failOpen: Boolean = false,
        private val addAudioEntered: CountDownLatch? = null,
        private val allowAddAudioToReturn: CountDownLatch? = null,
    ) : MoonshineSttRuntime {
        lateinit var session: FakeSession
        var onResponse: ((SttEngineResponse) -> Unit)? = null
        val closed = AtomicBoolean(false)

        override suspend fun openSession(
            updateIntervalSeconds: Double,
            onResponse: (SttEngineResponse) -> Unit,
        ): MoonshineSttSession {
            if (failOpen) throw IllegalStateException("simulated model load failure")
            this.onResponse = onResponse
            session = FakeSession(onResponse, addAudioEntered, allowAddAudioToReturn)
            return session
        }

        override fun close() {
            if (sessionInitialized()) {
                check(!session.inNativeCall.get()) { "runtime close raced addAudio" }
            }
            closed.set(true)
        }

        private fun sessionInitialized(): Boolean = this::session.isInitialized
    }

    private class FakeSession(
        private val onResponse: (SttEngineResponse) -> Unit,
        private val addAudioEntered: CountDownLatch?,
        private val allowAddAudioToReturn: CountDownLatch?,
    ) : MoonshineSttSession {
        val audio = mutableListOf<FloatArray>()
        val sampleRates = mutableListOf<Int>()
        val inNativeCall = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        var stopCount = 0

        override fun addAudio(
            samples: FloatArray,
            sampleRateHz: Int,
        ) {
            check(inNativeCall.compareAndSet(false, true)) { "overlapping native calls" }
            try {
                addAudioEntered?.countDown()
                if (allowAddAudioToReturn != null) {
                    check(allowAddAudioToReturn.await(2, TimeUnit.SECONDS)) { "test did not release fake native call" }
                }
                audio += samples.copyOf()
                sampleRates += sampleRateHz
                onResponse(SttEngineResponse.Partial("hello"))
            } finally {
                inNativeCall.set(false)
            }
        }

        override fun stopAndFlush() {
            check(!inNativeCall.get()) { "stop raced addAudio" }
            stopCount++
            onResponse(SttEngineResponse.Final("hello world"))
        }

        override fun close() {
            check(!inNativeCall.get()) { "stream release raced addAudio" }
            released.set(true)
        }
    }

    private suspend fun kotlinx.coroutines.Deferred<*>.awaitUnlessCompleted(timeoutMillis: Long): Boolean =
        withContext(Dispatchers.IO) {
            try {
                kotlinx.coroutines.withTimeout(timeoutMillis) {
                    await()
                    true
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                false
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
        }
}
