package dev.rubikxplorer.application.usecase

import dev.rubikxplorer.application.port.RandomSource
import kotlin.random.Random

/** Test-only [RandomSource] with a pinned seed, so generated Scrambles are reproducible. */
class SeededRandomSource(
    seed: Long,
) : RandomSource {
    private val random = Random(seed)

    override fun nextInt(until: Int): Int = random.nextInt(until)
}
