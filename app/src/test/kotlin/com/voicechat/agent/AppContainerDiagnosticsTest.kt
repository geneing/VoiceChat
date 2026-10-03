package com.voicechat.agent

import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.diagnostics.TraceJsonExporter
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppContainerDiagnosticsTest {
    @Test
    fun voiceFactoryUsesAppScopedSinkAndCloseDrainsEventsIntoRetainedStore() {
        val container = AppContainer(RuntimeEnvironment.getApplication())

        // Building the production factory must initialize and inject the app sink.
        container.voiceFactory
        val event =
            DiagnosticEvent(
                stage = DiagnosticStage.LLM_REQUEST,
                outcome = DiagnosticOutcome.PROGRESS,
                monotonicTimeNanos = 123L,
                turnId = TurnId("turn-test"),
                traceId = TraceId("trace-test"),
            )
        container.diagnosticsSink.record(event)

        container.close()

        assertEquals(listOf(event), container.traceStore.snapshot())
        assertTrue(TraceJsonExporter.export(container.traceStore.snapshot()).contains("\"trace-test\""))
    }
}
