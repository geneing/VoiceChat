package com.voicechat.agent.local

import com.voicechat.agent.contracts.LlmMessage
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRole
import com.voicechat.agent.contracts.consume
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M20 acceptance for the on-device adapters: request→stream mapping and typed
 * errors through fakes, with no native runtime and no real model.
 */
class LocalLanguageModelTest {
    private val modelId = LocalModels.GEMINI_NANO_ID
    private val request =
        LlmRequest(
            model = ProviderModelSelection(LocalProviders.AICORE, modelId),
            messages = listOf(LlmMessage(LlmRole.USER, "hello there")),
        )

    // --- AICore -----------------------------------------------------------------

    @Test
    fun theAicoreAdapterStreamsDeltasThenCompletesWithItsModelIdentity() =
        runTest {
            val generator = FakeLocalTextGenerator(chunks = listOf("Hello, ", "world"))
            val model = AicoreLanguageModel(generator)

            val result = model.consume(request)

            assertTrue(result.completed)
            assertEquals("Hello, world", result.text)
            assertEquals(2, result.deltaCount)
            assertEquals(modelId, result.model)
            assertEquals(LocalProviders.AICORE, model.providerId)
            // The bounded context is flattened to a role-labelled prompt.
            assertTrue(generator.prompts.single().startsWith("USER: hello there"))
        }

    @Test
    fun aGeneratorFailureBecomesATypedUnavailableFailure() =
        runTest {
            val generator =
                FakeLocalTextGenerator(
                    failure = VoiceAgentException(VoiceAgentError(ErrorCode.LLM_UNAVAILABLE, "runtime busy")),
                )

            val result = AicoreLanguageModel(generator).consume(request)

            assertFalse(result.completed)
            assertEquals(ErrorCode.LLM_UNAVAILABLE, result.error?.code)
        }

    @Test
    fun anUnexpectedGeneratorErrorIsClassifiedAsUnavailableNotSuccess() =
        runTest {
            val generator = FakeLocalTextGenerator(failure = IllegalStateException("boom"))

            val result = AicoreLanguageModel(generator).consume(request)

            assertFalse(result.completed)
            assertEquals(ErrorCode.LLM_UNAVAILABLE, result.error?.code)
        }

    @Test
    fun anUnsupportedReasoningRequestIsRefusedBeforeGenerating() =
        runTest {
            val generator = FakeLocalTextGenerator(chunks = listOf("ignored"))
            val result = AicoreLanguageModel(generator).consume(request.copy(reasoning = ReasoningLevel.HIGH))

            assertEquals(ErrorCode.LLM_INVALID_REQUEST, result.error?.code)
            assertTrue(generator.prompts.isEmpty())
        }

    // --- LiteRT-LM --------------------------------------------------------------

    @Test
    fun anInstalledVerifiedModelLoadsGeneratesAndClosesTheSession() =
        runTest {
            val store = InMemoryLocalModelFileStore()
            val installer = LocalModelInstaller(store)
            val bytes = "installed-model".toByteArray()
            val target = validatedSample(sha256 = LocalModelIntegrity.sha256Hex(bytes), downloadBytes = bytes.size.toLong())
            installer.install(target, bytes)
            val factory = FakeLiteRtLmSessionFactory(chunks = listOf("Hi"))
            val model = LiteRtLmLanguageModel(target, installer, factory)

            val result = model.consume(request)

            assertTrue(result.completed)
            assertEquals("Hi", result.text)
            assertEquals(target.descriptor.id, result.model)
            assertEquals(listOf(LocalModelInstaller.fileNameFor(target.artifact)), factory.openedPaths)
            assertEquals(
                "USER: hello there",
                factory.sessions
                    .single()
                    .prompts
                    .single(),
            )
            assertTrue("the session must be closed", factory.sessions.single().closed)
        }

    @Test
    fun anUninstalledModelFailsNotConfiguredAndNeverOpensASession() =
        runTest {
            val installer = LocalModelInstaller(InMemoryLocalModelFileStore())
            val factory = FakeLiteRtLmSessionFactory(chunks = listOf("nope"))
            val model = LiteRtLmLanguageModel(validatedSample(), installer, factory)

            val result = model.consume(request)

            assertEquals(ErrorCode.LLM_NOT_CONFIGURED, result.error?.code)
            assertTrue(factory.openedPaths.isEmpty())
        }

    @Test
    fun aCorruptInstalledModelFailsAndNeverOpensASession() =
        runTest {
            val store = InMemoryLocalModelFileStore()
            val installer = LocalModelInstaller(store)
            val target = validatedSample()
            // A file exists at the installed name but its bytes do not match the checksum.
            store.write(LocalModelInstaller.fileNameFor(target.artifact), "corrupted".toByteArray())
            val factory = FakeLiteRtLmSessionFactory()
            val model = LiteRtLmLanguageModel(target, installer, factory)

            val result = model.consume(request)

            assertEquals(ErrorCode.LLM_UNAVAILABLE, result.error?.code)
            assertTrue(factory.openedPaths.isEmpty())
        }

    @Test
    fun aSessionOpenFailureIsUnavailableAndNotSuccess() =
        runTest {
            val store = InMemoryLocalModelFileStore()
            val installer = LocalModelInstaller(store)
            val bytes = "installed-model".toByteArray()
            val target = validatedSample(sha256 = LocalModelIntegrity.sha256Hex(bytes), downloadBytes = bytes.size.toLong())
            installer.install(target, bytes)
            val factory = FakeLiteRtLmSessionFactory(openFailure = IllegalStateException("engine init failed"))
            val model = LiteRtLmLanguageModel(target, installer, factory)

            val result = model.consume(request)

            assertEquals(ErrorCode.LLM_UNAVAILABLE, result.error?.code)
        }

    @Test
    fun aMidStreamFailureKeepsThePartialText() =
        runTest {
            val store = InMemoryLocalModelFileStore()
            val installer = LocalModelInstaller(store)
            val bytes = "installed-model".toByteArray()
            val target = validatedSample(sha256 = LocalModelIntegrity.sha256Hex(bytes), downloadBytes = bytes.size.toLong())
            installer.install(target, bytes)
            val factory = FakeLiteRtLmSessionFactory(chunks = listOf("partial"), generateFailure = RuntimeException("oops"))
            val model = LiteRtLmLanguageModel(target, installer, factory)

            val result = model.consume(request)

            assertFalse(result.completed)
            assertTrue(result.isPartial)
            assertEquals(ErrorCode.LLM_UNAVAILABLE, result.error?.code)
        }
}
