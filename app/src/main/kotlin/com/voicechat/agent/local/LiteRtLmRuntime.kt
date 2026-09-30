package com.voicechat.agent.local

import kotlinx.coroutines.flow.Flow

/**
 * One open LiteRT-LM inference session over a single installed `.litertlm` bundle.
 *
 * It is the replaceable boundary between the app-managed model lifecycle and the
 * native runtime, so the LiteRT-LM adapter's request→stream mapping and typed
 * errors are unit tested on the JVM against a fake session with no native
 * library or model.
 */
interface LiteRtLmSession : AutoCloseable {
    /** Streams assistant-text deltas for [prompt]; failure is a thrown exception. */
    fun generate(prompt: String): Flow<String>
}

/**
 * Opens [LiteRtLmSession]s for a verified, installed model file.
 *
 * The real implementation is the only place the LiteRT-LM `Engine`/`Conversation`
 * API is touched ([EngineLiteRtLmSessionFactory]); holding it behind this factory
 * keeps the vendor AAR out of the app contract and out of JVM tests.
 */
fun interface LiteRtLmSessionFactory {
    /** Opens a session for the already integrity-verified [modelPath]. */
    suspend fun open(modelPath: String): LiteRtLmSession
}
