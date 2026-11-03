package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.predicate.WhiteCornersSolved
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved
import dev.rubikxplorer.domain.tutor.MoveFeedback
import dev.rubikxplorer.domain.tutor.Tutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Story 2.7 AC 2 (FR-4, AR-10): every shipped Lesson's authored step path, driven through [reduce]
 * from its entry state, reaches its Completion Predicate, and its authored harmful Move fails it.
 * Story 4.1 AC 4 (AD-6): every step's `done` holds in order along that path, and the last equals
 * `completion`.
 */
class ShippedLessonPathsTest {
    /** How a Lesson's entry state was made from SOLVED, and one Move that disturbs placed pieces. */
    private data class Path(
        val setup: String,
        val harmful: String,
    )

    private val table: Map<String, Path> =
        mapOf(
            "lesson-01-01" to Path("", "L"),
            "lesson-01-02" to Path("", "L"),
            "lesson-01-03" to Path("", "L"),
            "lesson-02-01" to Path("F2", "U"),
            "lesson-02-02" to Path("F2 D", "U"),
            "lesson-02-03" to Path("F", "U"),
            "lesson-02-04" to Path("F U' R U", "U"),
            "lesson-02-05" to Path("L2 B2 R2 F2", "U"),
            "lesson-03-01" to Path("R' D R", "U"),
            "lesson-03-02" to Path("F D' F'", "U"),
            "lesson-03-03" to Path("R' D R D' R' D2 R", "U"),
            "lesson-03-04" to Path("F' D F L' D L B' D B R' D R", "U"),
        )

    private val lessons: List<Lesson> by lazy {
        val result = ContentLoader.load(ContentSource.SHIPPED)
        assertInstanceOf(ContentLoadResult.Loaded::class.java, result, result.toString())
        (result as ContentLoadResult.Loaded).bundle.stages.flatMap { it.lessons }
    }

    private fun moves(notation: String): List<Move> {
        val parsed = Notation.parse(notation)
        assertInstanceOf(NotationResult.Parsed::class.java, parsed, notation)
        return (parsed as NotationResult.Parsed).moves
    }

    private fun SessionState.committing(moves: List<Move>): SessionState =
        moves.fold(this) { state, move -> reduce(state, commit(move)).state }

    private fun perLesson(check: (Lesson, Path) -> Unit): List<DynamicTest> =
        lessons.map { lesson -> DynamicTest.dynamicTest(lesson.id.value) { check(lesson, table.getValue(lesson.id.value)) } }

    @Test
    fun `the table has exactly one row per shipped Lesson`() {
        assertEquals(12, lessons.size)
        assertEquals(lessons.map { it.id.value }.toSet(), table.keys)
    }

    @TestFactory
    fun `each entry state is SOLVED with its setup applied`() =
        perLesson { lesson, path ->
            assertEquals(FaceletString.encode(CubeState.SOLVED.apply(moves(path.setup)).toFacelets()), lesson.entryState)
            assertEquals(CubeState.SOLVED.apply(moves(path.setup)), started(lesson).entry)
        }

    @TestFactory
    fun `each step path reaches the Completion Predicate through reduce`() =
        perLesson { lesson, _ ->
            var state = started(lesson)
            assertFalse(state.objectiveMet, "the entry state already meets the Objective")
            lesson.steps.forEachIndexed { index, step ->
                assertEquals(index, state.stepIndex)
                state = state.committing(moves(step.moves))
                val decision = checkNotNull(state.decision)
                assertTrue(decision.advance, "step ${index + 1} does not end on its path state")
                if (index < lesson.steps.lastIndex) {
                    assertFalse(decision.satisfied, "step ${index + 1} already meets the Objective")
                    state = reduce(state, stepAdvanced).state
                } else {
                    assertTrue(state.objectiveMet)
                    assertTrue(decision.satisfied)
                }
            }
        }

    @TestFactory
    fun `the harmful Move before the last step is HARMFUL, and after the path it fails the predicate`() =
        perLesson { lesson, path ->
            val harmful = moves(path.harmful)
            var state = started(lesson)
            lesson.steps.dropLast(1).forEach { step -> state = reduce(state.committing(moves(step.moves)), stepAdvanced).state }
            assertEquals(lesson.steps.lastIndex, state.stepIndex)
            val before = state.virtual
            val harmed = state.committing(harmful)
            assertEquals(MoveFeedback.HARMFUL, MoveFeedback.classify(lesson.completion, before, harmed.virtual))
            assertEquals(MoveFeedback.HARMFUL, harmed.lastMoveFeedback)

            val done = state.committing(moves(lesson.steps.last().moves))
            assertTrue(done.objectiveMet)
            val undone = done.committing(harmful)
            assertFalse(undone.objectiveMet)
            assertFalse(lesson.completion.isSatisfied(undone.virtual))
        }

    @TestFactory
    fun `the last step's done is the Completion Predicate`() =
        perLesson { lesson, _ -> assertEquals(lesson.completion, lesson.steps.last().done) }

    @TestFactory
    fun `along the authored path each step's done first holds at its end and keeps holding at every later boundary`() =
        perLesson { lesson, _ ->
            val entry = started(lesson).entry
            val stepMoves = lesson.steps.map { moves(it.moves) }
            // ends[k] is the number of Moves after which step k is complete.
            val ends = stepMoves.runningFold(0) { total, m -> total + m.size }.drop(1)
            val states = stepMoves.flatten().runningFold(entry) { state, move -> state.apply(move) }
            lesson.steps.forEachIndexed { k, step ->
                (0 until ends[k]).forEach { i ->
                    assertFalse(step.done.isSatisfied(states[i]), "step ${k + 1}'s done holds after $i Moves, before its last Move")
                }
                ends.drop(k).forEach { end ->
                    assertTrue(step.done.isSatisfied(states[end]), "step ${k + 1}'s done fails at the boundary after $end Moves")
                }
            }
        }

    @TestFactory
    fun `at each step boundary through reduce the card shows that step's Notice and Next moves on`() =
        perLesson { lesson, _ ->
            var state = started(lesson)
            lesson.steps.forEachIndexed { k, step ->
                assertEquals(k, state.stepIndex, "before step ${k + 1}")
                moves(step.moves).forEach { move ->
                    state = reduce(state, commit(move)).state
                }
                assertEquals(k, state.stepIndex, "at the end of step ${k + 1}")
                assertTrue(checkNotNull(state.decision).advance, "at the end of step ${k + 1}")
                if (k < lesson.steps.lastIndex) {
                    state = reduce(state, stepAdvanced).state
                    assertEquals(k + 1, state.stepIndex, "Next after step ${k + 1}")
                    assertFalse(checkNotNull(state.decision).advance, "Next after step ${k + 1}")
                }
            }
        }

    private fun lesson(id: String): Lesson = lessons.single { it.id.value == id }

    /** The state of [id]'s step path after its first [steps] steps, from its entry state. */
    private fun pathState(
        id: String,
        steps: Int,
    ): CubeState {
        val lesson = lesson(id)
        return Tutor.pathState(lesson, started(lesson).entry, steps)
    }

    @TestFactory
    fun `a Lesson that starts with its preserve predicate met keeps it at the end of every step`() =
        lessons
            .filter { it.preserve == WhiteCrossSolved && it.preserve.isSatisfied(started(it).entry) }
            .also { kept -> assertTrue(kept.containsAll(lessons.filter { it.stageId.value == "stage-03-first-layer-corners" })) }
            .map { lesson ->
                DynamicTest.dynamicTest(lesson.id.value) {
                    (1..lesson.steps.size).forEach { n ->
                        assertTrue(lesson.preserve.isSatisfied(pathState(lesson.id.value, n)), "after step $n")
                    }
                }
            }

    @Test
    fun `the first step of 02-04 and 03-03 leads to the entry state of 02-03 and 03-01`() {
        assertEquals(started(lesson("lesson-02-03")).entry, pathState("lesson-02-04", 1))
        assertEquals(started(lesson("lesson-03-01")).entry, pathState("lesson-03-03", 1))
    }

    @Test
    fun `each step of 03-04 solves exactly one more white corner`() {
        val steps = lesson("lesson-03-04").steps.size
        assertEquals(4, steps)
        assertEquals((0..steps).toList(), (0..steps).map { WhiteCornersSolved.progress(pathState("lesson-03-04", it)).done })
    }
}
