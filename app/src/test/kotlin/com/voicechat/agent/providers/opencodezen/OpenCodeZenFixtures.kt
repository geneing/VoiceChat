package com.voicechat.agent.providers.opencodezen

import com.voicechat.agent.remote.RecordedFixtures

/**
 * Loads the OpenCode Zen recorded SSE fixtures from `src/test/resources` (M18).
 *
 * The loader is deliberately local to this package rather than added to the
 * shared transport harness, so concurrent provider branches do not collide on
 * one file.
 */
object OpenCodeZenFixtures {
    /** Reads a recorded fixture under `llm/opencodezen/`. */
    fun text(name: String): String = RecordedFixtures.text("llm/opencodezen/$name")
}
