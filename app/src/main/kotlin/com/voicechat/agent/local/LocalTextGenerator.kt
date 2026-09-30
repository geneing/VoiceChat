package com.voicechat.agent.local

import com.voicechat.agent.contracts.LlmCapabilities
import kotlinx.coroutines.flow.Flow

/**
 * Vendor-neutral text generator over one local runtime (M20).
 *
 * It is the narrow seam the LiteRT-LM and AICore adapters share, so their
 * request→stream and error mapping can be unit tested on the JVM with a fake and
 * no native runtime or model. A generator emits assistant-text deltas only; the
 * terminal `Completed`/`Failed` events are the adapter's job, exactly as for the
 * M14–M19 remote adapters.
 *
 * A generator is a **single request** surface: [generate] returns a cold flow
 * that runs one generation when collected. Cancelling collection cancels the
 * generation. It never downloads or otherwise mutates the model.
 */
interface LocalTextGenerator {
    /** What the underlying runtime declares; never inferred by the adapter. */
    val capabilities: LlmCapabilities

    /** Streams assistant text for [prompt]; failure is a thrown exception. */
    fun generate(prompt: String): Flow<String>
}
