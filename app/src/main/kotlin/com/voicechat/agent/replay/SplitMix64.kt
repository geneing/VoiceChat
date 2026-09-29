package com.voicechat.agent.replay

/**
 * SplitMix64 pseudo-random generator used by the replay harness.
 *
 * Fixture bytes must be reproducible independently of the Kotlin runtime's RNG
 * implementation and of the host platform, so the harness carries its own
 * small, fixed algorithm instead of `kotlin.random.Random`. SplitMix64 is a
 * well-known generator with no dependencies; the constants are the published
 * ones.
 *
 * This type is internal: the replay layer exposes seeds, not RNG state.
 */
internal class SplitMix64(
    seed: Long,
) {
    private var state: Long = seed

    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    /** Uniform value in `[0, 1)`. */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / TWO_POW_53

    /** Uniform value in `[-1, 1)`. */
    fun nextBipolar(): Double = nextDouble() * 2.0 - 1.0

    private companion object {
        val GOLDEN_GAMMA: Long = 0x9E3779B97F4A7C15uL.toLong()
        val MIX_1: Long = 0xBF58476D1CE4E5B9uL.toLong()
        val MIX_2: Long = 0x94D049BB133111EBuL.toLong()
        val TWO_POW_53: Double = (1L shl 53).toDouble()
    }
}
