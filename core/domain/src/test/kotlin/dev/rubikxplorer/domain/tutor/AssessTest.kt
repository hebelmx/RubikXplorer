package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.MoveHistory
import dev.rubikxplorer.domain.text.Text
import dev.rubikxplorer.domain.tutor.TutorFixtures.CONFIG
import dev.rubikxplorer.domain.tutor.TutorFixtures.moves
import dev.rubikxplorer.domain.tutor.TutorFixtures.oneStep
import dev.rubikxplorer.domain.tutor.TutorFixtures.state
import dev.rubikxplorer.domain.tutor.TutorFixtures.twoStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 4.1 I/O matrix rows 1–5 and 7: Certainty from one enumerator, and the virtual-mode decision. */
class AssessTest {
    /** A fixed scrambled expected state. */
    private val s: CubeState = state("D2 B' R2 U F' L D' B2 R' U2 F")

    private val why = Text("msg.diagnosis.wrong-move-or-direction")

    private fun enumerator(expected: CubeState = s) = CandidateEnumerator(expected, 2, CONFIG.moves)

    @Test
    fun `virtual confirm is verified, as only the empty sequence fits`() {
        assertEquals(Certainty.VERIFIED, Assess.confirm(enumerator(), s))
        assertEquals(listOf(Candidate(emptyList(), s)), enumerator().consistentWith(s))
    }

    @Test
    fun `one-move evidence gives a verified Diagnosis naming that Move`() {
        val d = Assess.diagnose(ErrorCategory.WRONG_MOVE_OR_DIRECTION, enumerator(), s.apply(moves("R")), why)
        assertEquals(Certainty.VERIFIED, d.certainty)
        assertEquals(listOf(moves("R")), d.candidates.map { it.moves })
        assertEquals(ErrorCategory.WRONG_MOVE_OR_DIRECTION, d.category)
        assertEquals(RecoveryPath.UNDO, d.recoveryPath)
        assertEquals(why, d.explanation)
        assertEquals(emptySet<Any>(), d.disturbed)
    }

    @Test
    fun `ambiguous evidence is likely, never verified`() {
        val observed = s.apply(moves("U D"))
        val d = Assess.diagnose(ErrorCategory.WRONG_PIECE, enumerator(), observed, why)
        assertEquals(Certainty.LIKELY, d.certainty)
        assertEquals(setOf(moves("U D"), moves("D U")), d.candidates.map { it.moves }.toSet())
        assertEquals(Certainty.LIKELY, Assess.confirm(enumerator(), observed))
    }

    @Test
    fun `evidence out of reach is unknown`() {
        val observed = s.apply(moves("R U F"))
        val d = Assess.diagnose(ErrorCategory.UNKNOWN_DISCREPANCY, enumerator(), observed, why)
        assertEquals(Certainty.UNKNOWN, d.certainty)
        assertEquals(emptyList<Candidate>(), d.candidates)
        assertEquals(Certainty.UNKNOWN, Assess.confirm(enumerator(), observed))
    }

    @Test
    fun `the verified smart constructor refuses any count but one`() {
        val two = enumerator().consistentWith(s.apply(moves("U D")))
        assertEquals(2, two.size)
        assertEquals(DiagnosisResult.NotUnique(2), Diagnosis.verified(ErrorCategory.WRONG_PIECE, two, why))
        assertEquals(DiagnosisResult.NotUnique(0), Diagnosis.verified(ErrorCategory.WRONG_PIECE, emptyList(), why))
        val one = Diagnosis.verified(ErrorCategory.WRONG_PIECE, enumerator().consistentWith(s.apply(moves("R"))), why)
        assertInstanceOf(DiagnosisResult.Verified::class.java, one)
        assertEquals(Certainty.VERIFIED, (one as DiagnosisResult.Verified).diagnosis.certainty)
    }

    // Row 7: the virtual-mode decision.

    private fun history(
        lesson: Lesson,
        vararg played: String,
    ): MoveHistory {
        val start = TutorFixtures.entryOf(lesson)
        return played.flatMap { moves(it) }.fold(MoveHistory(start)) { h, m -> h.commit(m) }
    }

    private fun decide(
        lesson: Lesson,
        vararg played: String,
        mode: TeachingMode = TeachingMode.GUIDE_ME,
        revealRequested: Boolean = false,
        noticeStep: Int? = null,
        lastMoveFeedback: MoveFeedback? = null,
        lastDiagnosis: Diagnosis? = null,
    ) = Assess.decide(lesson, history(lesson, *played), CONFIG, mode, revealRequested, noticeStep, lastMoveFeedback, lastDiagnosis)

    @Test
    fun `a virtual decision is verified, with no Diagnosis and no Guided recovery`() {
        listOf(decide(oneStep), decide(oneStep, "R"), decide(oneStep, "F'"), decide(twoStep, "U", noticeStep = 0)).forEach { d ->
            assertEquals(Certainty.VERIFIED, d.certainty)
            assertNull(d.diagnosis)
            assertEquals(Recovery.Unavailable, d.recovery)
        }
    }

    @Test
    fun `guide me at the entry state reveals nothing, cannot advance and explains the Goal`() {
        val d = decide(oneStep)
        assertEquals(
            TutorDecision(
                objectiveId = oneStep.objective,
                satisfied = false,
                diagnosis = null,
                certainty = Certainty.VERIFIED,
                nextAction = NextAction.TURN,
                explanation = Text("lesson.lesson-01-01.goal.1"),
                recovery = Recovery.Unavailable,
                reveal = false,
                advance = false,
                feedback = null,
            ),
            d,
        )
    }

    @Test
    fun `show me always reveals and watches, and a reveal request reveals in guide me`() {
        val shown = decide(oneStep, mode = TeachingMode.SHOW_ME)
        assertTrue(shown.reveal)
        assertEquals(NextAction.WATCH, shown.nextAction)
        assertTrue(decide(oneStep, revealRequested = true).reveal)
    }

    @Test
    fun `satisfied is the predicate on the current state in every mode, and finishes`() {
        TeachingMode.entries.forEach { mode ->
            val met = decide(oneStep, "F'", mode = mode)
            assertTrue(met.satisfied, "$mode")
            assertTrue(met.advance, "$mode")
            assertEquals(NextAction.FINISH, met.nextAction, "$mode")
            assertEquals(Text("lesson.lesson-01-01.notice.1"), met.explanation, "$mode")
            assertFalse(decide(oneStep, "R", mode = mode).satisfied, "$mode")
        }
    }

    @Test
    fun `a pending Notice advances to the next step and explains that step's Notice`() {
        val d = decide(twoStep, "U", noticeStep = 0)
        assertTrue(d.advance)
        assertFalse(d.satisfied)
        assertEquals(NextAction.NEXT_STEP, d.nextAction)
        assertEquals(Text("lesson.lesson-01-01.notice.1"), d.explanation)
        val after = decide(twoStep, "U")
        assertFalse(after.advance)
        assertEquals(Text("lesson.lesson-01-01.goal.2"), after.explanation)
    }

    @Test
    fun `test me and let me try reveal only on request`() {
        listOf(TeachingMode.TEST_ME, TeachingMode.LET_ME_TRY).forEach { mode ->
            assertFalse(decide(oneStep, mode = mode).reveal, "$mode")
            assertFalse(decide(oneStep, "D", mode = mode, lastMoveFeedback = MoveFeedback.NEUTRAL).reveal, "$mode")
            assertTrue(decide(oneStep, mode = mode, revealRequested = true).reveal, "$mode")
        }
    }

    @Test
    fun `feedback shows only in test me, also beside the Notice or completion of the Move that finished a step`() {
        MoveFeedback.entries.forEach { feedback ->
            TeachingMode.entries.forEach { mode ->
                val shown = decide(oneStep, "D", mode = mode, lastMoveFeedback = feedback).feedback
                assertEquals(if (mode == TeachingMode.TEST_ME) feedback else null, shown, "$mode $feedback")
            }
        }
        assertNull(decide(oneStep, "D", mode = TeachingMode.TEST_ME).feedback)
        val done = decide(oneStep, "F'", mode = TeachingMode.TEST_ME, lastMoveFeedback = MoveFeedback.ON_PATH)
        assertTrue(done.advance)
        assertEquals(MoveFeedback.ON_PATH, done.feedback)
        assertEquals(Text("lesson.lesson-01-01.notice.1"), done.explanation)
        val atNotice = decide(twoStep, "U", mode = TeachingMode.TEST_ME, noticeStep = 0, lastMoveFeedback = MoveFeedback.ON_PATH)
        assertTrue(atNotice.advance)
        assertEquals(MoveFeedback.ON_PATH, atNotice.feedback)
        assertNull(decide(twoStep, "U", mode = TeachingMode.GUIDE_ME, noticeStep = 0, lastMoveFeedback = MoveFeedback.ON_PATH).feedback)
    }

    @Test
    fun `a diagnosis shows only in test me while the step is not done, and explains the decision`() {
        val diagnosis = Assess.diagnose(ErrorCategory.WRONG_MOVE_OR_DIRECTION, enumerator(), s.apply(moves("R")), why)
        TeachingMode.entries.forEach { mode ->
            val d = decide(oneStep, "D", mode = mode, lastMoveFeedback = MoveFeedback.HARMFUL, lastDiagnosis = diagnosis)
            if (mode == TeachingMode.TEST_ME) {
                assertEquals(diagnosis, d.diagnosis)
                assertEquals(why, d.explanation)
                assertEquals(MoveFeedback.HARMFUL, d.feedback)
            } else {
                assertNull(d.diagnosis, "$mode")
                assertEquals(Text("lesson.lesson-01-01.goal.1"), d.explanation, "$mode")
            }
            assertEquals(Recovery.Unavailable, d.recovery)
        }
        val done = decide(oneStep, "F'", mode = TeachingMode.TEST_ME, lastDiagnosis = diagnosis)
        assertNull(done.diagnosis)
        assertEquals(Text("lesson.lesson-01-01.notice.1"), done.explanation)
    }
}
