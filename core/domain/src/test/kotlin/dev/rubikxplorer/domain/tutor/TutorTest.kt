package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.LessonId
import dev.rubikxplorer.domain.content.RecoveryRef
import dev.rubikxplorer.domain.content.StageId
import dev.rubikxplorer.domain.content.Step
import dev.rubikxplorer.domain.content.TextKey
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** AC 3–4: reveal per mode, advance on the path state or the predicate, satisfied independent of mode. */
class TutorTest {
    private fun moves(text: String): List<Move> = (Notation.parse(text) as NotationResult.Parsed).moves

    private fun state(text: String): CubeState = CubeState.SOLVED.apply(moves(text))

    private fun lesson(
        entry: String,
        vararg stepMoves: String,
    ): Lesson =
        Lesson(
            id = LessonId("lesson-01-01"),
            stageId = StageId("stage-01-basics"),
            prerequisites = emptyList(),
            objective = TextKey("lesson.lesson-01-01.objective"),
            entryState = FaceletString.encode(state(entry).toFacelets()),
            demonstration = stepMoves.joinToString(" "),
            learnerAction = TextKey("lesson.lesson-01-01.learner-action"),
            completion = WhiteCrossSolved,
            preserve = WhiteCrossSolved,
            recovery = RecoveryRef("recovery-undo-last-move"),
            steps =
                stepMoves.mapIndexed { i, m ->
                    Step(
                        goal = TextKey("lesson.lesson-01-01.goal.${i + 1}"),
                        preserve = TextKey("lesson.lesson-01-01.keep.${i + 1}"),
                        watch = TextKey("lesson.lesson-01-01.watch.${i + 1}"),
                        notice = TextKey("lesson.lesson-01-01.notice.${i + 1}"),
                        moves = m,
                    )
                },
        )

    private val oneStep = lesson("F", "F'")
    private val twoStep = lesson("F U'", "U", "F'")

    private fun decide(
        lesson: Lesson,
        entry: String,
        current: String,
        mode: TeachingMode = TeachingMode.GUIDE_ME,
        stepIndex: Int = 0,
        revealRequested: Boolean = false,
        lastMoveFeedback: MoveFeedback? = null,
    ) = Tutor.decide(lesson, state(entry), state(current), mode, stepIndex, revealRequested, lastMoveFeedback)

    @Test
    fun `guide me at the entry state reveals nothing and cannot advance`() {
        val d = decide(oneStep, "F", "F")
        assertEquals(TutorDecision(oneStep.objective, satisfied = false, reveal = false, advance = false, feedback = null), d)
    }

    @Test
    fun `show me always reveals and a reveal request reveals in guide me`() {
        assertTrue(decide(oneStep, "F", "F", mode = TeachingMode.SHOW_ME).reveal)
        assertTrue(decide(oneStep, "F", "F", revealRequested = true).reveal)
    }

    @Test
    fun `satisfied is the predicate on the current state in every mode`() {
        TeachingMode.entries.forEach { mode ->
            val met = decide(oneStep, "F", "F F'", mode = mode)
            assertTrue(met.satisfied, "$mode")
            assertTrue(met.advance, "$mode")
            assertFalse(decide(oneStep, "F", "F R", mode = mode).satisfied, "$mode")
        }
    }

    @Test
    fun `an intermediate step advances when its path state is reached, without satisfying the lesson`() {
        val d = decide(twoStep, "F U'", "F U' U")
        assertTrue(d.advance)
        assertFalse(d.satisfied)
        assertFalse(decide(twoStep, "F U'", "F U' R").advance)
        // On step 2 the step-1 path state is no longer enough.
        assertFalse(decide(twoStep, "F U'", "F U' U", stepIndex = 1).advance)
        assertTrue(decide(twoStep, "F U'", "F U' U F'", stepIndex = 1).advance)
    }

    @Test
    fun `the path state applies the first k steps and clamps past either end`() {
        val entry = state("F U'")
        assertEquals(entry, Tutor.pathState(twoStep, entry, 0))
        assertEquals(entry, Tutor.pathState(twoStep, entry, -1))
        assertEquals(state("F"), Tutor.pathState(twoStep, entry, 1))
        assertEquals(CubeState.SOLVED, Tutor.pathState(twoStep, entry, 2))
        assertEquals(CubeState.SOLVED, Tutor.pathState(twoStep, entry, 5))
    }

    @Test
    fun `invalid step notation is a programmer error`() {
        val broken = oneStep.copy(steps = listOf(oneStep.steps.single().copy(moves = "Q")))
        assertThrows<IllegalStateException> { Tutor.pathState(broken, CubeState.SOLVED, 1) }
    }

    @Test
    fun `test me and let me try reveal only on request`() {
        listOf(TeachingMode.TEST_ME, TeachingMode.LET_ME_TRY).forEach { mode ->
            assertFalse(decide(oneStep, "F", "F", mode = mode).reveal, "$mode")
            assertFalse(decide(oneStep, "F", "F D", mode = mode, lastMoveFeedback = MoveFeedback.NEUTRAL).reveal, "$mode")
            assertTrue(decide(oneStep, "F", "F", mode = mode, revealRequested = true).reveal, "$mode")
        }
    }

    @Test
    fun `feedback shows only in test me and only while the step is not done`() {
        MoveFeedback.entries.forEach { feedback ->
            TeachingMode.entries.forEach { mode ->
                val shown = decide(oneStep, "F", "F D", mode = mode, lastMoveFeedback = feedback).feedback
                assertEquals(if (mode == TeachingMode.TEST_ME) feedback else null, shown, "$mode $feedback")
            }
        }
        assertNull(decide(oneStep, "F", "F D", mode = TeachingMode.TEST_ME).feedback)
        // Once the step is done, Notice and Next speak instead.
        val done = decide(oneStep, "F", "F F'", mode = TeachingMode.TEST_ME, lastMoveFeedback = MoveFeedback.PROGRESS)
        assertTrue(done.advance)
        assertNull(done.feedback)
        val atPathState = decide(twoStep, "F U'", "F U' U", mode = TeachingMode.TEST_ME, lastMoveFeedback = MoveFeedback.PROGRESS)
        assertTrue(atPathState.advance)
        assertNull(atPathState.feedback)
    }

    @Test
    fun `the modes are ordered show me, guide me, test me, let me try`() {
        assertEquals(
            listOf(TeachingMode.SHOW_ME, TeachingMode.GUIDE_ME, TeachingMode.TEST_ME, TeachingMode.LET_ME_TRY),
            TeachingMode.entries,
        )
    }

    @Test
    fun `guide me is the default mode`() {
        assertEquals(TeachingMode.GUIDE_ME, TeachingMode.DEFAULT)
    }
}
