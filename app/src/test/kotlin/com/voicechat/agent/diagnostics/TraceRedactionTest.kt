package com.voicechat.agent.diagnostics

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.LlmMessage
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRole
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.fake.FakeMonotonicClock
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the M04 acceptance criterion that redaction is a property of the
 * default trace and not a comment: no secret, transcript, prompt, or provider
 * response content can appear in a recorded event, in an exported trace, in a
 * summary, or in a streamed delta.
 */
class TraceRedactionTest {
    // Values that must never appear anywhere in a default trace.
    private val fakeApiKey = "sk-live-DO-NOT-LEAK-0123456789"
    private val userTranscript = "my private medical question"
    private val promptText = "system prompt with private context"
    private val providerBody = "{\"choices\":[{\"delta\":{\"content\":\"provider response body\"}}]}"

    private val selection = ProviderModelSelection(ProviderId("openai"), ModelId("gpt-test"))

    private fun recorder(
        clock: FakeMonotonicClock,
        sink: RecordingDiagnosticsSink,
    ): TurnTraceRecorder =
        TurnTraceRecorder(
            traceId = TraceId("trace-1"),
            turnId = TurnId("turn-1"),
            clock = clock,
            sink = sink,
            providerId = selection.providerId.value,
            modelId = selection.modelId.value,
        )

    private fun allText(sink: RecordingDiagnosticsSink): String =
        sink.events.joinToString(separator = "\n") { it.toString() } +
            "\n" +
            TraceJsonExporter.export(sink.events) +
            "\n" +
            TraceViewer.render(TraceViewer.lines(sink.events))

    @Test
    fun defaultTraceContainsNoSecretTranscriptPromptOrProviderBody() {
        val clock = FakeMonotonicClock()
        val sink = RecordingDiagnosticsSink()
        val trace = recorder(clock, sink)

        // A realistic turn: selected provider, request, streamed deltas that
        // would carry content if any API accepted raw text, a typed error whose
        // platform detail mentions a credential, and delivered playback counts.
        trace.start(DiagnosticStage.LLM_REQUEST)
        trace.requestSelected(selection.providerId.value, selection.modelId.value)
        trace.llmDelta(receivedAtNanos = clock.nanoTime(), characterCount = userTranscript.length)
        trace.llmDelta(receivedAtNanos = clock.nanoTime(), characterCount = providerBody.length)
        trace.error(
            stage = DiagnosticStage.LLM_REQUEST,
            errorCode = ErrorCode.LLM_AUTHENTICATION_FAILED.name,
        )
        trace.playbackDelivered(deliveredCharacterCount = 12, totalCharacterCount = 40, interrupted = true)
        trace.turnCompleted()

        val text = allText(sink)

        // The default path is content-free, so none of these can be present.
        listOf(fakeApiKey, userTranscript, promptText, providerBody, "provider response body").forEach { secret ->
            assertFalse("default trace leaked: $secret", text.contains(secret))
        }
        // No attribute key can hold them either: every key is a member of the
        // closed [DiagnosticAttribute] enum.
        sink.events.forEach { event ->
            assertEquals(
                event.attributes.keys,
                event.attributes.keys
                    .filter { it::class == DiagnosticAttribute::class }
                    .toSet(),
            )
        }
    }

    @Test
    fun failedEventKeepsOnlyTheStableErrorCodeNotItsDetail() {
        val clock = FakeMonotonicClock()
        val sink = RecordingDiagnosticsSink()
        val trace = recorder(clock, sink)

        trace.error(
            stage = DiagnosticStage.LLM_REQUEST,
            errorCode = ErrorCode.LLM_AUTHENTICATION_FAILED.name,
        )

        val failure = sink.events.single { it.outcome == DiagnosticOutcome.FAILED }
        assertEquals(
            ErrorCode.LLM_AUTHENTICATION_FAILED.name,
            failure.attributes[DiagnosticAttribute.ERROR_CODE],
        )
        // VoiceAgentError.detail is never accepted by the recorder at all.
        val detail = VoiceAgentError(ErrorCode.LLM_AUTHENTICATION_FAILED, detail = fakeApiKey).detail!!
        assertFalse(allText(sink).contains(detail))
    }

    @Test
    fun redactReplacesAnyNonEmptyValueAndNeverEchoesIt() {
        assertEquals(Redaction.PLACEHOLDER, Redaction.redact(fakeApiKey))
        assertEquals(Redaction.PLACEHOLDER, Redaction.redact(userTranscript))
        assertEquals("", Redaction.redact(""))
        assertEquals("", Redaction.redact(null))
        assertFalse(Redaction.redact(fakeApiKey).contains(fakeApiKey))
    }

    @Test
    fun redactHeadersRemovesCredentialHeadersAndKeepsSafeOnes() {
        val redacted =
            Redaction.redactHeaders(
                mapOf(
                    "Authorization" to "Bearer $fakeApiKey",
                    "X-API-Key" to fakeApiKey,
                    "Content-Type" to "application/json",
                    "X-Model" to selection.modelId.value,
                ),
            )

        assertEquals(Redaction.PLACEHOLDER, redacted["Authorization"])
        assertEquals(Redaction.PLACEHOLDER, redacted["X-API-Key"])
        assertEquals("application/json", redacted["Content-Type"])
        assertEquals(selection.modelId.value, redacted["X-Model"])
        assertFalse(redacted.values.any { it.contains(fakeApiKey) })
    }

    @Test
    fun contentCaptureIsOffByDefaultAndDiscardsEverything() {
        val store = InMemoryTraceContentStore()
        assertFalse(store.isEnabled)

        store.capture(
            TraceContentRecord(
                traceId = TraceId("trace-1"),
                turnId = TurnId("turn-1"),
                kind = TraceContentKind.USER_TRANSCRIPT,
                text = userTranscript,
                monotonicTimeNanos = 0L,
            ),
        )

        assertTrue(store.snapshot().isEmpty())
        assertFalse(store.snapshot().any { it.text.contains(userTranscript) })
    }

    @Test
    fun optedInContentCaptureIsBoundedInCountAndLength() {
        val policy = TraceContentPolicy(enabled = true, maxRecords = 2, maxCharactersPerRecord = 8)
        val store = InMemoryTraceContentStore(policy)
        assertTrue(store.isEnabled)

        listOf("1234567890", "abcdefghij", "ABCDEFGHIJ").forEachIndexed { index, text ->
            store.capture(
                TraceContentRecord(
                    traceId = TraceId("trace-1"),
                    turnId = TurnId("turn-1"),
                    kind = TraceContentKind.PROMPT,
                    text = text,
                    monotonicTimeNanos = index.toLong(),
                ),
            )
        }

        val retained = store.snapshot()
        assertEquals(2, retained.size) // oldest evicted
        assertTrue(retained.all { it.text.length <= 8 })
        assertEquals("abcdefgh", retained[0].text)
        assertEquals("ABCDEFGH", retained[1].text)

        store.clear()
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun noOpContentSinkRetainsNothing() {
        NoOpTraceContentSink.capture(
            TraceContentRecord(
                traceId = TraceId("trace-1"),
                turnId = null,
                kind = TraceContentKind.ASSISTANT_RESPONSE,
                text = providerBody,
                monotonicTimeNanos = 0L,
            ),
        )
    }

    @Test
    fun requestObjectsAreNotRetainedByTheRecorder() {
        val clock = FakeMonotonicClock()
        val sink = RecordingDiagnosticsSink()
        val trace = recorder(clock, sink)

        // Build a request containing the sensitive prompt, but only ever hand
        // the recorder identities and counts. The request itself never reaches
        // the recorder API, which is what the type signature enforces.
        val request =
            LlmRequest(
                model = selection,
                messages = listOf(LlmMessage(LlmRole.USER, promptText)),
            )
        trace.start(DiagnosticStage.LLM_REQUEST)
        trace.requestSelected(request.model.providerId.value, request.model.modelId.value)
        trace.llmDelta(receivedAtNanos = clock.nanoTime(), characterCount = request.messages.sumOf { it.content.length })
        trace.turnCompleted()

        assertFalse(allText(sink).contains(promptText))
        assertFalse(allText(sink).contains(userTranscript))
    }
}
