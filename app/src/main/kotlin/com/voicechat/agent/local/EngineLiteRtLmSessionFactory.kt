package com.voicechat.agent.local

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// The only file that imports the LiteRT-LM runtime API (`com.google.ai.edge.litertlm`).
// Verified against the LiteRT-LM Android guide (accessed 2026-09-29):
// https://ai.google.dev/edge/litert-lm/android
// Engine.initialize() may take up to ~10s and must run off the main thread.

/**
 * The real [LiteRtLmSessionFactory] over the LiteRT-LM `Engine`.
 *
 * The engine is created and initialized on [Dispatchers.IO]; a failure to
 * initialize closes the engine and propagates, so a load failure is never
 * reported as a successful init. The engine is always closed with its session.
 *
 * [configFactory] exists so a test or a future backend choice (CPU/GPU/NPU) can
 * supply a different [EngineConfig] without changing this class.
 */
class EngineLiteRtLmSessionFactory(
    private val configFactory: (String) -> EngineConfig = { modelPath -> EngineConfig(modelPath = modelPath) },
) : LiteRtLmSessionFactory {
    override suspend fun open(modelPath: String): LiteRtLmSession =
        withContext(Dispatchers.IO) {
            val engine = Engine(configFactory(modelPath))
            try {
                engine.initialize()
            } catch (failure: Throwable) {
                runCatching { engine.close() }
                throw failure
            }
            val conversation =
                try {
                    engine.createConversation()
                } catch (failure: Throwable) {
                    runCatching { engine.close() }
                    throw failure
                }
            EngineSession(engine, conversation)
        }
}

/** One engine + conversation pair, closed together. */
private class EngineSession(
    private val engine: Engine,
    private val conversation: Conversation,
) : LiteRtLmSession {
    override fun generate(prompt: String): Flow<String> =
        conversation.sendMessageAsync(prompt).map { message ->
            message.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(separator = "") { it.text }
        }

    override fun close() {
        runCatching { conversation.close() }
        runCatching { engine.close() }
    }
}
