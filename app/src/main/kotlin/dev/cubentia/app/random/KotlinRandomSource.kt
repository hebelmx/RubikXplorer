package dev.cubentia.app.random

import dev.cubentia.application.port.RandomSource
import kotlin.random.Random

/** The [RandomSource] adapter over [kotlin.random.Random] (AD-11); tests pass a seeded [Random]. */
class KotlinRandomSource(
    private val random: Random = Random.Default,
) : RandomSource {
    override fun nextInt(until: Int): Int = random.nextInt(until)
}
