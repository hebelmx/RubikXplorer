package dev.cubentia.domain.tutor

import dev.cubentia.domain.content.Lesson
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.predicate.AllOf
import dev.cubentia.domain.predicate.WhiteCrossSolved
import dev.cubentia.domain.text.Text
import dev.cubentia.domain.tutor.TutorFixtures.CONFIG
import dev.cubentia.domain.tutor.TutorFixtures.entryOf
import dev.cubentia.domain.tutor.TutorFixtures.moves
import dev.cubentia.domain.tutor.TutorFixtures.shipped
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Story 4.2 classification matrix (AD-5): [Assess.classify] judges a *Test me* Move against the step
 * derived from the state before it, in the order ON_PATH → HARMFUL → PROGRESS → NEUTRAL.
 */
class ClassifyTest {
    /** Plays [played] from [lesson]'s entry state, classifying each Move against the state before it. */
    private fun play(
        lesson: Lesson,
        played: String,
        from: CubeState = entryOf(lesson),
    ): List<MoveAssessment> {
        val entry = entryOf(lesson)
        var state = from
        return moves(played).map { move ->
            val after = state.apply(move)
            Assess.classify(lesson, entry, state, move, after, CONFIG).also { state = after }
        }
    }

    private fun one(
        lesson: Lesson,
        move: String,
        from: CubeState = entryOf(lesson),
    ): MoveAssessment = play(lesson, move, from).single()

    private fun atStep(
        lesson: Lesson,
        steps: Int,
    ): CubeState = Tutor.pathState(lesson, entryOf(lesson), steps)

    @Test
    fun `the next authored Move is on path even when it lifts a solved piece, with the on-path line`() {
        val lesson = shipped("lesson-03-01")
        val result = one(lesson, "R'")
        assertEquals(MoveAssessment(MoveFeedback.ON_PATH, null), result)
        assertEquals(Text("msg.test-me.on-path"), result.feedback.text)
        // R' takes the white-red edge out of the cross for a moment.
        assertTrue(WhiteCrossSolved.isSatisfied(entryOf(lesson)))
        assertTrue(!WhiteCrossSolved.isSatisfied(entryOf(lesson).apply(moves("R'"))))
    }

    @TestFactory
    fun `every taught Move of every shipped Lesson is on path from its path state, never harmful`() =
        shipped.map { lesson ->
            DynamicTest.dynamicTest(lesson.id.value) {
                val path = lesson.steps.flatMap { moves(it.moves) }
                var state = entryOf(lesson)
                path.forEachIndexed { i, move ->
                    val after = state.apply(move)
                    val result = Assess.classify(lesson, entryOf(lesson), state, move, after, CONFIG)
                    assertEquals(MoveAssessment(MoveFeedback.ON_PATH, null), result, "Move ${i + 1}: $move")
                    state = after
                }
            }
        }

    @Test
    fun `a half turn by quarters is on path both times, in either direction`() {
        val lesson = shipped("lesson-02-05")
        assertEquals(listOf(MoveFeedback.ON_PATH, MoveFeedback.ON_PATH), play(lesson, "F F").map { it.feedback })
        assertEquals(listOf(MoveFeedback.ON_PATH, MoveFeedback.ON_PATH), play(lesson, "F' F'").map { it.feedback })
        assertEquals(MoveFeedback.ON_PATH, one(lesson, "F2").feedback)
        // A quarter of the half turn, then the other quarter, is not the authored half turn.
        assertNotEquals(MoveFeedback.ON_PATH, play(lesson, "F F'").last().feedback)
    }

    @Test
    fun `a Move that disturbs the cross a Lesson preserves is harmful, the completed layer disturbed`() {
        val result = one(shipped("lesson-03-01"), "R")
        assertEquals(MoveFeedback.HARMFUL, result.feedback)
        val d = checkNotNull(result.diagnosis)
        assertEquals(ErrorCategory.COMPLETED_LAYER_DISTURBED, d.category)
        assertEquals(Certainty.VERIFIED, d.certainty)
        assertEquals(listOf(moves("R")), d.candidates.map { it.moves })
        assertEquals(setOf(Pieces.WHITE_RED), d.disturbed)
        assertEquals(Text("msg.diagnosis.completed-layer-disturbed", mapOf("layer" to "cross")), d.explanation)
        assertEquals(RecoveryPath.GUIDED_RECOVERY, d.recoveryPath)
        assertEquals(Text("msg.recovery.guided-recovery"), d.recoveryPath.prompt)
    }

    @Test
    fun `moving an earlier step's piece while the target waits is harmful, the wrong piece`() {
        val lesson = shipped("lesson-02-05")
        val result = one(lesson, "F", from = atStep(lesson, 1))
        assertEquals(MoveFeedback.HARMFUL, result.feedback)
        val d = checkNotNull(result.diagnosis)
        assertEquals(ErrorCategory.WRONG_PIECE, d.category)
        assertEquals(Certainty.VERIFIED, d.certainty)
        assertEquals(setOf(Pieces.WHITE_GREEN), d.disturbed)
        assertEquals(Text("msg.diagnosis.wrong-piece", mapOf("moved" to "white_green", "target" to "white_red")), d.explanation)
        assertEquals(RecoveryPath.UNDO, d.recoveryPath)
    }

    @Test
    fun `moving a placed piece with no target piece named is harmful, the wrong move or direction`() {
        val lesson = shipped("lesson-02-04")
        val result = one(lesson, "F", from = atStep(lesson, 1))
        assertEquals(MoveFeedback.HARMFUL, result.feedback)
        val d = checkNotNull(result.diagnosis)
        assertEquals(ErrorCategory.WRONG_MOVE_OR_DIRECTION, d.category)
        assertEquals(Certainty.VERIFIED, d.certainty)
        assertEquals(Text("msg.diagnosis.wrong-move-or-direction"), d.explanation)
        assertEquals(Text("msg.recovery.undo"), d.recoveryPath.prompt)
    }

    @Test
    fun `a whole-cube rotation is neutral with the turned-cube diagnosis, and rotating back clears it`() {
        shipped.forEach { lesson ->
            val (turned, back) = play(lesson, "y y'")
            assertEquals(MoveFeedback.NEUTRAL, turned.feedback, "${lesson.id}")
            val d = checkNotNull(turned.diagnosis) { "${lesson.id}" }
            assertEquals(ErrorCategory.UNEXPECTED_ORIENTATION, d.category)
            assertEquals(Certainty.VERIFIED, d.certainty)
            assertEquals(Text("msg.diagnosis.unexpected-orientation", mapOf("bottom" to "yellow", "front" to "green")), d.explanation)
            assertEquals(RecoveryPath.REORIENT, d.recoveryPath)
            assertEquals(listOf(RecoveryOffer.SHOW_ME_HOW), d.recoveryPath.offers)
            assertEquals(MoveAssessment(MoveFeedback.NEUTRAL, null), back, "${lesson.id}")
        }
    }

    @Test
    fun `an off-path slice Move is judged with its cubies re-held, and two commuting candidates make it likely`() {
        val lesson = shipped("lesson-03-01")
        val result = one(lesson, "M")
        assertEquals(MoveFeedback.HARMFUL, result.feedback)
        val d = checkNotNull(result.diagnosis)
        assertEquals(ErrorCategory.COMPLETED_LAYER_DISTURBED, d.category)
        assertEquals(Certainty.LIKELY, d.certainty)
        assertEquals(setOf(moves("R L'"), moves("L' R")), d.candidates.map { it.moves }.toSet())
        assertEquals(Text("msg.diagnosis.completed-layer-disturbed", mapOf("layer" to "cross")), d.explanation)
    }

    @Test
    fun `an off-path wide Move is judged with its cubies re-held, as its one face turn`() {
        val lesson = shipped("lesson-03-01")
        val result = one(lesson, "r")
        assertEquals(MoveFeedback.HARMFUL, result.feedback)
        val d = checkNotNull(result.diagnosis)
        assertEquals(ErrorCategory.COMPLETED_LAYER_DISTURBED, d.category)
        assertEquals(Certainty.VERIFIED, d.certainty)
        assertEquals(listOf(moves("L")), d.candidates.map { it.moves })
    }

    @Test
    fun `stepping back with nothing preserved is neutral even though progress drops`() {
        val lesson = TutorFixtures.lesson("F U'", "U F'" to WhiteCrossSolved)
        val entry = entryOf(lesson)
        val (forward, back) = play(lesson, "U U'")
        assertEquals(MoveFeedback.ON_PATH, forward.feedback)
        assertEquals(MoveAssessment(MoveFeedback.NEUTRAL, null), back)
        val done = AllOf(listOf(lesson.preserve, lesson.steps.single().done))
        val atU = entry.apply(moves("U"))
        assertTrue(done.progress(entry) < done.progress(atU), "the step back lowers the step's progress")

        // Off the path too: 02-01's U lifts its three placed cross edges, but no step has kept them.
        val crossLesson = shipped("lesson-02-01")
        assertEquals(MoveAssessment(MoveFeedback.NEUTRAL, null), one(crossLesson, "U"))
    }

    @Test
    fun `an off-path Move that raises the step's progress and disturbs nothing is progress`() {
        val lesson = shipped("lesson-02-01")
        val (lifted, back) = play(lesson, "U U'")
        assertEquals(MoveFeedback.NEUTRAL, lifted.feedback)
        assertEquals(MoveAssessment(MoveFeedback.PROGRESS, null), back)
        assertEquals(Text("msg.test-me.progress"), back.feedback.text)
    }

    @Test
    fun `harmful is exactly a disturbance diagnosis, and only off the path`() {
        val all = shipped.flatMap { lesson -> (0..lesson.steps.size).map { lesson to atStep(lesson, it) } }
        all.forEach { (lesson, before) ->
            CONFIG.moves.forEach { move: Move ->
                val result = one(lesson, Notation.format(move), before)
                val disturbance = result.diagnosis?.category in DiagnosisRules.DISTURBANCE
                assertEquals(disturbance, result.feedback == MoveFeedback.HARMFUL, "${lesson.id} $move")
                if (result.feedback == MoveFeedback.ON_PATH) assertNull(result.diagnosis)
            }
        }
    }
}
