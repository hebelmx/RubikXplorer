package dev.cubentia.domain.method

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.cube.CubeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The FR-15 corpus for the beginner method (Story 15.1): solved, 10,000 uniform random legal states
 * and 100 deep Scrambles. Every result must solve its input, keep every earlier Stage's predicate at
 * each segment boundary, and list its Stages in Course order. Prints median, p95 and max time.
 */
class MethodSolveCorpusTest {
    @Test
    fun `the beginner method solves the FR-15 corpus`() {
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val order: List<StageId> = MethodSolve.FIRST_TWO_LAYER_STAGES + MethodSolve.LAST_LAYER_STAGES
        val completions =
            order.associateWith { id ->
                bundle.stages
                    .single { it.id == id }
                    .lessons
                    .last()
                    .completion
            }
        val random = Random(SEED)
        val corpus =
            listOf(CubeState.SOLVED) +
                List(UNIFORM) { RandomLegalStates.randomLegalState(random) } +
                List(DEEP) { RandomLegalStates.deepScramble(random) }
        val warmUp = Random(WARM_UP_SEED)
        repeat(WARM_UP) { MethodSolve.solve(RandomLegalStates.randomLegalState(warmUp), SolveMethod.BEGINNER, bundle) }

        val times = LongArray(corpus.size)
        var moves = 0L
        corpus.forEachIndexed { index, state ->
            val started = System.nanoTime()
            val result = MethodSolve.solve(state, SolveMethod.BEGINNER, bundle)
            times[index] = System.nanoTime() - started
            assertTrue(result is MethodSolveResult.Solved, "case $index: $result")
            val segments = (result as MethodSolveResult.Solved).segments
            val tags = segments.map { (it.tag as SegmentTag.OfStage).stageId }
            assertEquals(order.filter { it in tags }, tags, "case $index")
            var current = state
            for (segment in segments) {
                assertTrue(segment.moves.isNotEmpty(), "case $index")
                current = current.apply(segment.moves)
                val stage = (segment.tag as SegmentTag.OfStage).stageId
                order.take(order.indexOf(stage) + 1).forEach { id ->
                    assertTrue(completions.getValue(id).isSatisfied(current), "case $index: $id broken after $stage")
                }
                moves += segment.moves.size
            }
            assertTrue(current.isSolved, "case $index")
        }
        val sorted = times.sorted()

        fun ms(nanos: Long) = "%.2f".format(nanos / 1_000_000.0)
        println(
            "MethodSolve BEGINNER corpus (seed $SEED): ${corpus.size} cases, 0 failures; " +
                "median ${ms(sorted[sorted.size / 2])} ms, p95 ${ms(sorted[(sorted.size * 95) / 100])} ms, " +
                "max ${ms(sorted.last())} ms, total ${ms(times.sum())} ms; mean ${moves / corpus.size} Moves",
        )
    }

    private companion object {
        const val SEED = 20_261_122L
        const val WARM_UP_SEED = 1L
        const val UNIFORM = 10_000
        const val DEEP = 100
        const val WARM_UP = 200
    }
}
