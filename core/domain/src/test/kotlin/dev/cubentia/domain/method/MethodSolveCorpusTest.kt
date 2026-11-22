package dev.cubentia.domain.method

import dev.cubentia.domain.content.Algorithm
import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The FR-15 corpus for the beginner method (Story 15.1): solved, 10,000 uniform random legal states
 * and 100 deep Scrambles. Every result must solve its input, keep every earlier Stage's predicate at
 * each segment boundary, and list its Stages in Course order. Prints median, p95 and max time.
 *
 * Story 15.2 runs the same corpus for 2-look and full OLL + PLL: every result solves its input, keeps
 * the first-two-layer predicates at every boundary after they are reached, conforms to its method, and
 * every last-layer segment is (rotation?)(U?)(an Algorithm of the chosen set for its Case)(U?).
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

    @Test
    fun `the 2-look method solves the FR-15 corpus`() = faster(SolveMethod.TWO_LOOK)

    @Test
    fun `the full OLL + PLL method solves the FR-15 corpus`() = faster(SolveMethod.FULL_OLL_PLL)

    private fun moves(notation: String): List<Move> = (Notation.parse(notation) as NotationResult.Parsed).moves

    /** The chosen set with no statuses, read straight from content: 2-look Lessons' Algorithms, or every primary. */
    private fun setOf(
        method: SolveMethod,
        bundle: ContentBundle,
    ): List<Algorithm> =
        if (method == SolveMethod.TWO_LOOK) {
            val ids =
                bundle.twoLookStages
                    .flatMap { it.lessons }
                    .flatMap { bundle.lessonAlgorithms.getValue(it.id) }
                    .toSet()
            bundle.algorithms.filter { it.id in ids }
        } else {
            bundle.algorithms.filter { it.isPrimary }
        }

    private fun isUTurn(move: Move?): Boolean = move is FaceTurn && move.face == Face.U

    private fun faster(method: SolveMethod) {
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val f2l = MethodSolve.FIRST_TWO_LAYER_STAGES
        val completions =
            f2l.map { id ->
                bundle.stages
                    .single { it.id == id }
                    .lessons
                    .last()
                    .completion
            }
        val bodies = setOf(method, bundle).groupBy({ it.caseId }, { moves(it.notation) })
        val loneStage = SegmentTag.OfStage(MethodSolve.lastLayerStageOf(method)!!)
        val random = Random(SEED)
        val corpus =
            listOf(CubeState.SOLVED) +
                List(UNIFORM) { RandomLegalStates.randomLegalState(random) } +
                List(DEEP) { RandomLegalStates.deepScramble(random) }
        val warmUp = Random(WARM_UP_SEED)
        repeat(WARM_UP) { MethodSolve.solve(RandomLegalStates.randomLegalState(warmUp), method, bundle) }

        val times = LongArray(corpus.size)
        var moves = 0L
        var caseSegments = 0L
        corpus.forEachIndexed { index, state ->
            val started = System.nanoTime()
            val result = MethodSolve.solve(state, method, bundle)
            times[index] = System.nanoTime() - started
            assertTrue(result is MethodSolveResult.Solved, "case $index: $result")
            val segments = (result as MethodSolveResult.Solved).segments
            assertTrue(MethodSolve.conforms(segments, method, bundle), "case $index")
            val firstTwoLayers = segments.takeWhile { (it.tag as? SegmentTag.OfStage)?.stageId in f2l }
            val tags = firstTwoLayers.map { (it.tag as SegmentTag.OfStage).stageId }
            assertEquals(f2l.filter { it in tags }, tags, "case $index")
            var current = state
            for (segment in segments) {
                assertTrue(segment.moves.isNotEmpty(), "case $index")
                current = current.apply(segment.moves)
                val stage = (segment.tag as? SegmentTag.OfStage)?.stageId
                val kept = if (stage in f2l) completions.take(f2l.indexOf(stage) + 1) else completions
                kept.forEach { assertTrue(it.isSatisfied(current), "case $index: $it broken after ${segment.tag}") }
                moves += segment.moves.size
            }
            assertTrue(current.isSolved, "case $index")
            val lastLayer = segments.drop(firstTwoLayers.size)
            if (lastLayer.size == 1 && lastLayer.single().tag == loneStage) {
                val rest = lastLayer.single().moves.dropWhile { it is CubeRotation }
                assertTrue(rest.size == 1 && isUTurn(rest.single()), "case $index: ${lastLayer.single().moves}")
                return@forEachIndexed
            }
            // The cube starts the last layer held as the input; after the one rotation yellow is on U.
            var held = state.apply(firstTwoLayers.flatMap { it.moves })
            lastLayer.forEachIndexed { at, segment ->
                caseSegments++
                val caseId = (segment.tag as SegmentTag.OfCase).caseId
                var rest = segment.moves
                // The leading rotation is the method's only when yellow is not yet up; otherwise it is the Algorithm's own (PLL Aa, E).
                if (at == 0 && rest.first() is CubeRotation && held.orientation.colorAt(Face.U) != Color.YELLOW) {
                    held = held.apply(rest.first())
                    rest = rest.drop(1)
                }
                assertTrue(
                    rest.none { it is CubeRotation } ||
                        bodies.getValue(caseId).any { body ->
                            body.any { it is CubeRotation }
                        },
                    "case $index",
                )
                assertEquals(Color.YELLOW, held.orientation.colorAt(Face.U), "case $index: yellow not up before $caseId")
                if (isUTurn(rest.first())) rest = rest.drop(1).also { held = held.apply(rest.first()) }
                val body = bodies.getValue(caseId).firstOrNull { rest.take(it.size) == it }
                assertTrue(body != null, "case $index: $caseId ${segment.moves}")
                held = held.apply(body!!)
                val after = rest.drop(body.size)
                assertTrue(
                    after.isEmpty() || (at == lastLayer.lastIndex && after.size == 1 && isUTurn(after.single())),
                    "case $index: ${segment.moves}",
                )
                held = held.apply(after)
            }
            assertTrue(held.isSolved, "case $index")
        }
        val sorted = times.sorted()

        fun ms(nanos: Long) = "%.2f".format(nanos / 1_000_000.0)
        println(
            "MethodSolve $method corpus (seed $SEED): ${corpus.size} cases, 0 failures; " +
                "median ${ms(sorted[sorted.size / 2])} ms, p95 ${ms(sorted[(sorted.size * 95) / 100])} ms, " +
                "max ${ms(sorted.last())} ms, total ${ms(times.sum())} ms; mean ${moves / corpus.size} Moves, " +
                "$caseSegments Case segments",
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
