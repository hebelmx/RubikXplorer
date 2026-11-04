package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.predicate.Piece
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved
import dev.rubikxplorer.domain.text.Text
import dev.rubikxplorer.domain.tutor.TutorFixtures.CONFIG
import dev.rubikxplorer.domain.tutor.TutorFixtures.entryOf
import dev.rubikxplorer.domain.tutor.TutorFixtures.moves
import dev.rubikxplorer.domain.tutor.TutorFixtures.shipped
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Story 4.5 (FR-12, AD-5): the bounded IDA* Guided recovery planner, its goal and its copy. */
class RecoveryPlannerTest {
    private val lesson = shipped("lesson-03-01")
    private val entry = entryOf(lesson)
    private val goal = RecoveryPlanner.goal(lesson, listOf(entry, entry))

    private val crossEdges: Set<Piece> =
        setOf(Color.GREEN, Color.RED, Color.BLUE, Color.ORANGE).map { Piece(setOf(Color.WHITE, it)) }.toSet()

    private fun plan(
        from: CubeState,
        maxDepth: Int = CONFIG.recoveryMaxDepth,
        shouldContinue: () -> Boolean = { true },
    ): RecoveryPlan = RecoveryPlanner.plan(from, goal, maxDepth, CONFIG.moves, shouldContinue)

    @Test
    fun `the goal of a corner Lesson keeps the four cross edges`() {
        assertEquals(listOf(WhiteCrossSolved), goal.keep)
        assertEquals(crossEdges, goal.pieces)
        assertTrue(goal.isMet(entry))
        assertFalse(goal.isMet(entry.apply(moves("R"))))
    }

    @Test
    fun `the goal unions the references and adds the steps done there`() {
        val solved = entry.apply(moves("R' D' R"))
        assertTrue(lesson.completion.isSatisfied(solved))
        val both = RecoveryPlanner.goal(lesson, listOf(entry, solved))
        assertEquals(listOf(lesson.preserve, lesson.steps.single().done), both.keep)
        assertEquals(
            lesson.steps
                .single()
                .done
                .preservedPieces(solved),
            both.pieces,
        )
        assertEquals(8, both.pieces.size)
    }

    @Test
    fun `a goal already met is an empty plan`() {
        assertEquals(RecoveryPlan.Found(emptyList()), plan(entry))
        assertEquals(RecoveryPlan.Found(emptyList()), plan(entry, maxDepth = 0))
        assertEquals(RecoveryPlan.Found(emptyList()), RecoveryPlanner.plan(entry, RecoveryGoal(emptyList(), emptySet()), 1, CONFIG.moves))
    }

    @Test
    fun `one bad turn is undone by its inverse`() {
        assertEquals(RecoveryPlan.Found(moves("R'")), plan(entry.apply(moves("R"))))
    }

    @Test
    fun `several bad turns give a shortest multi-move plan`() {
        val observed = entry.apply(moves("R U F"))
        val found = plan(observed)
        assertInstanceOf(RecoveryPlan.Found::class.java, found)
        val planned = (found as RecoveryPlan.Found).moves
        assertTrue(planned.size in 1..3, "$planned")
        assertTrue(goal.isMet(observed.apply(planned)))
        planned.zipWithNext().forEach { (a, b) -> assertFalse((a as FaceTurn).face == (b as FaceTurn).face, "$planned") }
        // Brute force: no shorter sequence meets the goal.
        val shorter = CandidateEnumerator(observed, planned.size - 1, CONFIG.moves).candidates()
        assertTrue(shorter.none { goal.isMet(it.state) }, "a shorter plan exists than $planned")
        // Deterministic.
        assertEquals(found, plan(observed))
    }

    @Test
    fun `no plan within the bound is NotFound`() {
        val observed = entry.apply(moves("R U F"))
        assertEquals(RecoveryPlan.NotFound, plan(observed, maxDepth = 1))
        assertTrue(CandidateEnumerator(observed, 1, CONFIG.moves).candidates().none { goal.isMet(it.state) })
    }

    @Test
    fun `the search stops when asked, checking every 1024 nodes`() {
        val scrambled = CubeState.SOLVED.apply(moves("R U F' L D2 B R' U2 F L'"))
        val far = RecoveryPlanner.goal(lesson, listOf(CubeState.SOLVED))
        var asked = 0
        val result =
            RecoveryPlanner.plan(scrambled, far, 8, CONFIG.moves) {
                asked++
                asked < 3
            }
        assertEquals(RecoveryPlan.Stopped, result)
        assertEquals(3, asked)
        assertEquals(RecoveryPlan.Stopped, RecoveryPlanner.plan(scrambled, far, 8, CONFIG.moves) { false })
    }

    @Test
    fun `a negative bound is a programmer error`() {
        assertThrows<IllegalArgumentException> { plan(entry, maxDepth = -1) }
    }

    @Test
    fun `the plan copy counts the moves and names what stays`() {
        assertEquals(Text("msg.recovery.plan", mapOf("count" to 1, "layer" to "cross")), RecoveryPlanner.describe(moves("R'"), crossEdges))
        val corner = Piece(setOf(Color.WHITE, Color.GREEN, Color.RED))
        val corners =
            listOf(Color.GREEN to Color.RED, Color.RED to Color.BLUE, Color.BLUE to Color.ORANGE, Color.ORANGE to Color.GREEN)
                .map { (a, b) -> Piece(setOf(Color.WHITE, a, b)) }
                .toSet()
        assertEquals(
            Text("msg.recovery.plan", mapOf("count" to 3, "layer" to "first_layer")),
            RecoveryPlanner.describe(moves("R U R'"), crossEdges + corners),
        )
        // A partial cross or layer is never called whole.
        assertEquals(
            Text("msg.recovery.plan", mapOf("count" to 1, "layer" to "other")),
            RecoveryPlanner.describe(moves("F'"), setOf(Piece(setOf(Color.WHITE, Color.GREEN)))),
        )
        assertEquals(
            Text("msg.recovery.plan", mapOf("count" to 3, "layer" to "other")),
            RecoveryPlanner.describe(moves("R U R'"), crossEdges + corner),
        )
        assertEquals(
            Text("msg.recovery.plan", mapOf("count" to 2, "layer" to "other")),
            RecoveryPlanner.describe(moves("R U"), setOf(Piece(setOf(Color.YELLOW, Color.RED)))),
        )
        assertEquals(Text("msg.recovery.pending"), RecoveryPlanner.PENDING)
        assertEquals(Text("msg.recovery.not-found"), RecoveryPlanner.NOT_FOUND)
    }
}
