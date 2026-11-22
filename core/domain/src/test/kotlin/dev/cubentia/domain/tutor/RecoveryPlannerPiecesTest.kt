package dev.cubentia.domain.tutor

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.method.MethodSolve
import dev.cubentia.domain.method.RandomLegalStates
import dev.cubentia.domain.predicate.AllOf
import dev.cubentia.domain.predicate.MiddleLayerSolved
import dev.cubentia.domain.predicate.Piece
import dev.cubentia.domain.predicate.Predicate
import dev.cubentia.domain.predicate.WhiteCornersSolved
import dev.cubentia.domain.predicate.WhiteCrossSolved
import dev.cubentia.domain.tutor.TutorFixtures.CONFIG
import dev.cubentia.domain.tutor.TutorFixtures.moves
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

/** Story 15.1 (AD-21): the piece-tracking bounded IDA* behind the first-two-layer segments. */
class RecoveryPlannerPiecesTest {
    private val bundle by lazy { (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle }

    /** The first-two-layer Stage completions as shipped, plus their leaves. */
    private val predicates: List<Predicate> by lazy {
        val completions =
            MethodSolve.FIRST_TWO_LAYER_STAGES.map { id ->
                bundle.stages
                    .single { it.id == id }
                    .lessons
                    .last()
                    .completion
            }
        listOf(WhiteCrossSolved, WhiteCornersSolved, MiddleLayerSolved) + completions
    }

    private val crossEdges: Set<Piece> = WhiteCrossSolved.preservedPieces(CubeState.SOLVED)

    private fun home(
        state: CubeState,
        piece: Piece,
    ): Boolean {
        val id = checkNotNull(PieceDistances.idOf(piece))
        return PieceDistances.positionOf(state.cubies, id) == PieceDistances.home(id)
    }

    @Test
    fun `the first-two-layer predicates preserve a piece exactly when its cubie is home and untwisted`() {
        val random = Random(1_501)
        val states =
            List(300) { RandomLegalStates.randomLegalState(random) } +
                List(300) { CubeState.SOLVED.apply(List(random.nextInt(1, 6)) { randomTurn(random) }) }
        assertEquals(6, predicates.size)
        assertInstanceOf(AllOf::class.java, predicates.last())
        for ((index, state) in states.withIndex()) {
            val held = state.reoriented(Orientation.ALL[index % Orientation.ALL.size])
            for (predicate in predicates) {
                val goal = predicate.preservedPieces(CubeState.SOLVED)
                val expected = goal.filter { home(held, it) }.toSet()
                assertEquals(expected, predicate.preservedPieces(held), "$predicate at $index")
            }
        }
    }

    @Test
    fun `every piece has a cubie and its home has distance zero`() {
        val pieces = predicates.last().preservedPieces(CubeState.SOLVED)
        assertEquals(12, pieces.size)
        val ids = pieces.map { checkNotNull(PieceDistances.idOf(it)) }.toSet()
        assertEquals(12, ids.size)
        assertEquals(null, PieceDistances.idOf(Piece(emptySet())))
        ids.forEach { id -> assertEquals(0, PieceDistances.table(intArrayOf(id))[PieceDistances.home(id)].toInt()) }
        assertThrows<IllegalArgumentException> { PieceDistances.table(IntArray(4) { it }) }
    }

    @Test
    fun `a goal already met is an empty plan`() {
        val goal = RecoveryGoal(listOf(WhiteCrossSolved), crossEdges)
        assertEquals(RecoveryPlan.Found(emptyList()), RecoveryPlanner.planPieces(CubeState.SOLVED, goal, 0, CONFIG.moves))
        assertEquals(RecoveryPlan.Found(emptyList()), RecoveryPlanner.planPieces(CubeState.SOLVED.apply(moves("D")), goal, 3, CONFIG.moves))
    }

    @Test
    fun `plans meet the goal with the same length as plan on small cases`() {
        val random = Random(77)
        val goal = RecoveryGoal(listOf(WhiteCrossSolved), crossEdges)
        repeat(40) { index ->
            val scrambled = CubeState.SOLVED.apply(List(random.nextInt(1, 4)) { randomTurn(random) })
            val from = scrambled.reoriented(Orientation.ALL[index % Orientation.ALL.size])
            val shortest = RecoveryPlanner.plan(from, goal, 4, CONFIG.moves)
            val pieces = RecoveryPlanner.planPieces(from, goal, 4, CONFIG.moves)
            assertInstanceOf(RecoveryPlan.Found::class.java, pieces)
            val planned = (pieces as RecoveryPlan.Found).moves
            assertTrue(goal.isMet(from.apply(planned)), "$planned from $from")
            assertEquals((shortest as RecoveryPlan.Found).moves.size, planned.size, "$planned vs ${shortest.moves}")
            planned.zipWithNext().forEach { (a, b) -> assertFalse((a as FaceTurn).face == (b as FaceTurn).face, "$planned") }
        }
    }

    @Test
    fun `a middle-layer edge is placed keeping the first layer`() {
        val stage4 = predicates.last()
        val first = predicates[predicates.size - 2]
        val from = CubeState.SOLVED.apply(moves("D' R' D R D F D' F'").map { it.inverse() }.reversed())
        assertTrue(first.isSatisfied(from))
        assertFalse(stage4.isSatisfied(from))
        val goal = RecoveryGoal(listOf(first, stage4), stage4.preservedPieces(CubeState.SOLVED))
        val found = assertInstanceOf(RecoveryPlan.Found::class.java, RecoveryPlanner.planPieces(from, goal, 8, CONFIG.moves))
        assertTrue(stage4.isSatisfied(from.apply(found.moves)))
        assertTrue(found.moves.size <= 8)
    }

    @Test
    fun `the bound is honoured`() {
        val goal = RecoveryGoal(listOf(WhiteCrossSolved), crossEdges)
        val from = CubeState.SOLVED.apply(moves("R U F"))
        assertEquals(RecoveryPlan.NotFound, RecoveryPlanner.planPieces(from, goal, 1, CONFIG.moves))
        assertThrows<IllegalArgumentException> { RecoveryPlanner.planPieces(from, goal, -1, CONFIG.moves) }
    }

    @Test
    fun `a search told to stop returns Stopped`() {
        val stage4 = predicates.last()
        val goal = RecoveryGoal(listOf(stage4), stage4.preservedPieces(CubeState.SOLVED))
        val from = RandomLegalStates.randomLegalState(Random(5))
        var asked = 0
        val result =
            RecoveryPlanner.planPieces(from, goal, 20, CONFIG.moves) {
                asked++
                false
            }
        assertEquals(RecoveryPlan.Stopped, result)
        assertEquals(1, asked)
    }

    @Test
    fun `only face turns are searched`() {
        val goal = RecoveryGoal(listOf(WhiteCrossSolved), crossEdges)
        val from = CubeState.SOLVED.apply(moves("R"))
        assertThrows<IllegalArgumentException> {
            RecoveryPlanner.planPieces(from, goal, 2, listOf(CubeRotation(dev.cubentia.domain.cube.Axis.Y, Turn.CLOCKWISE)))
        }
    }

    private fun randomTurn(random: Random) = FaceTurn(Face.entries[random.nextInt(6)], Turn.entries[random.nextInt(3)])
}
