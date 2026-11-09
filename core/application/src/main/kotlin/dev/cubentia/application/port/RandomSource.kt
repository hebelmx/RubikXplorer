package dev.cubentia.application.port

/**
 * Port to a source of uniform random integers, injected wherever a use case needs randomness (AD-11).
 * It exposes no seed: seeds belong only to test fakes, and results such as Scrambles are kept as
 * explicit Moves, never persisted as a seed in place of Moves.
 */
interface RandomSource {
    /** A uniformly random value in `0 until until`; [until] is positive. */
    fun nextInt(until: Int): Int
}
