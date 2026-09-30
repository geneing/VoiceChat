package com.voicechat.agent.providers.opencodego

import com.voicechat.agent.remote.RecordedFixtures

/**
 * Loads the OpenCode Go recorded SSE fixtures from `src/test/resources` (M17).
 *
 * The loader is deliberately local to this package rather than added to the
 * shared transport harness, so the concurrent M15/M16 provider branches do not
 * collide on one file.
 */
object OpenCodeGoFixtures {
    /** Reads a recorded fixture under `llm/opencodego/`. */
    fun text(name: String): String = RecordedFixtures.text("llm/opencodego/$name")
}
