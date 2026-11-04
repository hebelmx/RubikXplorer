package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.predicate.WhiteLayer
import dev.rubikxplorer.domain.predicate.WhitePieceSolved
import dev.rubikxplorer.domain.text.Text
import dev.rubikxplorer.domain.tutor.TutorFixtures.CONFIG
import dev.rubikxplorer.domain.tutor.TutorFixtures.entryOf
import dev.rubikxplorer.domain.tutor.TutorFixtures.moves
import dev.rubikxplorer.domain.tutor.TutorFixtures.shipped
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Story 4.2 AC 1–2 (FR-9, AD-5): one scenario per Error Category through [Assess.diagnose] and
 * [Assess.diagnoseEntry], each with its category, Certainty, keyed copy and Recovery Path; ambiguous
 * evidence is LIKELY and evidence out of reach UNKNOWN, never VERIFIED.
 */
class DiagnosisScenarioTest {
    private fun diagnose(
        lesson: Lesson,
        expected: CubeState,
        observed: CubeState,
    ): Diagnosis? = Assess.diagnose(lesson, entryOf(lesson), expected, observed, CONFIG)

    private fun at(
        lesson: Lesson,
        steps: Int,
    ): CubeState = Tutor.pathState(lesson, entryOf(lesson), steps)

    private fun assertDiagnosis(
        d: Diagnosis?,
        category: ErrorCategory,
        certainty: Certainty,
        explanation: Text,
        path: RecoveryPath,
    ) {
        checkNotNull(d) { "expected a $category Diagnosis" }
        assertEquals(category, d.category)
        assertEquals(certainty, d.certainty)
        assertEquals(explanation, d.explanation)
        assertEquals(path, d.recoveryPath)
    }

    @Test
    fun `wrong move or direction`() {
        val lesson = shipped("lesson-02-04")
        val expected = at(lesson, 1)
        val d = diagnose(lesson, expected, expected.apply(moves("F")))
        assertDiagnosis(
            d,
            ErrorCategory.WRONG_MOVE_OR_DIRECTION,
            Certainty.VERIFIED,
            Text("msg.diagnosis.wrong-move-or-direction"),
            RecoveryPath.UNDO,
        )
        assertEquals(listOf(moves("F")), d!!.candidates.map { it.moves })
        assertEquals(Text("msg.recovery.undo"), d.recoveryPath.prompt)
    }

    @Test
    fun `wrong piece`() {
        val lesson = shipped("lesson-02-05")
        val expected = at(lesson, 1)
        assertDiagnosis(
            diagnose(lesson, expected, expected.apply(moves("F"))),
            ErrorCategory.WRONG_PIECE,
            Certainty.VERIFIED,
            Text("msg.diagnosis.wrong-piece", mapOf("moved" to "white_green", "target" to "white_red")),
            RecoveryPath.UNDO,
        )
    }

    @Test
    fun `wrong piece orientation`() {
        // A Lesson whose one step watches the white-green-red corner.
        val corner = "white-green-red"
        val lesson = TutorFixtures.lesson("", "R' D' R" to WhitePieceSolved(corner), completion = WhitePieceSolved(corner))
        // (R' D' R D) twice twists the corner in its slot; one R' before that seat takes it out.
        val twisted = CubeState.SOLVED.apply(moves("R' D' R D R' D' R D"))
        assertTrue(WhiteLayer.misorientedInSlot(twisted, Pieces.WHITE_GREEN_RED))
        val expected = twisted.apply(moves("R'"))
        assertFalse(WhiteLayer.misorientedInSlot(expected, Pieces.WHITE_GREEN_RED))
        val observed = expected.apply(moves("R"))
        val d = diagnose(lesson, expected, observed)
        assertDiagnosis(
            d,
            ErrorCategory.WRONG_PIECE_ORIENTATION,
            Certainty.VERIFIED,
            Text("msg.diagnosis.wrong-piece-orientation", mapOf("kind" to "corner", "face" to "up")),
            RecoveryPath.GUIDED_RECOVERY,
        )
        // Not a disturbance: classify never calls it harmful.
        val classified = Assess.classify(lesson, entryOf(lesson), expected, moves("R").single(), observed, CONFIG)
        assertTrue(classified.feedback != MoveFeedback.HARMFUL)
        assertEquals(ErrorCategory.WRONG_PIECE_ORIENTATION, classified.diagnosis?.category)
    }

    @Test
    fun `wrong piece orientation of an edge, seated flipped`() {
        // A Lesson whose one step watches the white-green edge.
        val edge = "white-green"
        val lesson = TutorFixtures.lesson("", "F" to WhitePieceSolved(edge), completion = WhitePieceSolved(edge))
        // U' R' F' seats the white-green edge in its slot flipped; one F' before that takes it out.
        val flipped = CubeState.SOLVED.apply(moves("U' R' F'"))
        assertTrue(WhiteLayer.misorientedInSlot(flipped, Pieces.WHITE_GREEN))
        val expected = flipped.apply(moves("F'"))
        assertFalse(WhiteLayer.misorientedInSlot(expected, Pieces.WHITE_GREEN))
        val d = diagnose(lesson, expected, expected.apply(moves("F")))
        assertDiagnosis(
            d,
            ErrorCategory.WRONG_PIECE_ORIENTATION,
            Certainty.VERIFIED,
            Text("msg.diagnosis.wrong-piece-orientation", mapOf("kind" to "edge", "face" to "up")),
            RecoveryPath.GUIDED_RECOVERY,
        )
    }

    @Test
    fun `unexpected orientation offers show me how and no undo`() {
        val lesson = shipped("lesson-02-01")
        val entry = entryOf(lesson)
        val d = diagnose(lesson, entry, entry.apply(moves("y")))
        assertDiagnosis(
            d,
            ErrorCategory.UNEXPECTED_ORIENTATION,
            Certainty.VERIFIED,
            Text("msg.diagnosis.unexpected-orientation", mapOf("bottom" to "yellow", "front" to "green")),
            RecoveryPath.REORIENT,
        )
        assertEquals(listOf(RecoveryOffer.SHOW_ME_HOW), d!!.recoveryPath.offers)
        assertFalse(RecoveryOffer.UNDO in d.recoveryPath.offers)
        assertEquals(Text("msg.recovery.reorient"), d.recoveryPath.prompt)
        // Back in the entry's orientation, nothing is wrong.
        assertNull(diagnose(lesson, entry.apply(moves("y")), entry.apply(moves("y y'"))))
    }

    @Test
    fun `completed layer disturbed`() {
        val lesson = shipped("lesson-03-01")
        val entry = entryOf(lesson)
        val d = diagnose(lesson, entry, entry.apply(moves("R")))
        assertDiagnosis(
            d,
            ErrorCategory.COMPLETED_LAYER_DISTURBED,
            Certainty.VERIFIED,
            Text("msg.diagnosis.completed-layer-disturbed", mapOf("layer" to "cross")),
            RecoveryPath.GUIDED_RECOVERY,
        )
        assertEquals(setOf(Pieces.WHITE_RED), d!!.disturbed)
        assertEquals(Text("msg.recovery.guided-recovery"), d.recoveryPath.prompt)
    }

    @Test
    fun `ambiguous evidence of a disturbed cross is likely, never verified`() {
        val lesson = shipped("lesson-03-01")
        val entry = entryOf(lesson)
        val d = diagnose(lesson, entry, entry.apply(moves("U D")))
        assertDiagnosis(
            d,
            ErrorCategory.COMPLETED_LAYER_DISTURBED,
            Certainty.LIKELY,
            Text("msg.diagnosis.completed-layer-disturbed", mapOf("layer" to "cross")),
            RecoveryPath.GUIDED_RECOVERY,
        )
        assertEquals(2, d!!.candidates.size)
    }

    @Test
    fun `unknown discrepancy beyond the enumerator's reach`() {
        val lesson = shipped("lesson-03-01")
        val entry = entryOf(lesson)
        val d = diagnose(lesson, entry, entry.apply(moves("R U F")))
        assertDiagnosis(
            d,
            ErrorCategory.UNKNOWN_DISCREPANCY,
            Certainty.UNKNOWN,
            Text("msg.diagnosis.unknown-discrepancy"),
            RecoveryPath.RETRY,
        )
        assertEquals(emptyList<Candidate>(), d!!.candidates)
        assertEquals(Text("msg.recovery.retry"), d.recoveryPath.prompt)
    }

    @Test
    fun `invalid entered state is unknown and explained by the validator's reason`() {
        val colors =
            CubeState.SOLVED
                .toFacelets()
                .colors
                .toMutableList()
        // Twist the U-R-F corner: its three stickers cycle.
        val (a, b, c) = Triple(colors[8], colors[9], colors[20])
        colors[8] = c
        colors[9] = a
        colors[20] = b
        val d = Assess.diagnoseEntry(Facelets(colors))
        assertDiagnosis(
            d,
            ErrorCategory.INVALID_ENTERED_STATE,
            Certainty.UNKNOWN,
            Text("msg.validation.twisted-corner"),
            RecoveryPath.RE_ENTER,
        )
        assertEquals(listOf(RecoveryOffer.RE_ENTER), d!!.recoveryPath.offers)
        assertNull(Assess.diagnoseEntry(CubeState.SOLVED.toFacelets()))
    }

    @Test
    fun `no change at all is no diagnosis`() {
        val lesson = shipped("lesson-03-01")
        assertNull(diagnose(lesson, entryOf(lesson), entryOf(lesson)))
    }
}
