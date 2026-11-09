package dev.cubentia.application.session

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.Lesson
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.text.Text
import dev.cubentia.domain.tutor.ErrorCategory
import dev.cubentia.domain.tutor.MoveFeedback
import dev.cubentia.domain.tutor.Recovery
import dev.cubentia.domain.tutor.RecoveryPath
import dev.cubentia.domain.tutor.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Story 4.2 AC 5–6 (FR-10, AD-5): a Harmful Move in *Test me* during the Stage 2 and Stage 3 taught
 * inserts is diagnosed in the decision, with its copy and Recovery Path prompt, while the session's
 * Cube State stays the Learner's own and Undo restores it.
 */
class DiagnosisSessionScenarioTest {
    private val lessons: List<Lesson> by lazy {
        val result = ContentLoader.load(ContentSource.SHIPPED)
        check(result is ContentLoadResult.Loaded) { "the shipped content does not load: $result" }
        result.bundle.stages.flatMap { it.lessons }
    }

    private fun lesson(id: String): Lesson = lessons.single { it.id.value == id }

    private fun moves(notation: String): List<Move> = (Notation.parse(notation) as NotationResult.Parsed).moves

    /** [id] in *Test me*, its first [steps] steps played and passed with *Next*. */
    private fun testMe(
        id: String,
        steps: Int,
    ): SessionState {
        val lesson = lesson(id)
        var state = started(lesson).after(modeChanged(TeachingMode.TEST_ME))
        lesson.steps.take(steps).forEach { step ->
            moves(step.moves).forEach { state = state.after(commit(it)) }
            state = state.after(stepAdvanced)
        }
        return state
    }

    private fun assertHarmful(
        before: SessionState,
        move: String,
        category: ErrorCategory,
        explanation: Text,
        prompt: Text,
    ) {
        val harmful = moves(move).single()
        val played = before.history.state
        val harmed = before.after(commit(harmful))
        val decision = checkNotNull(harmed.decision)
        assertEquals(MoveFeedback.HARMFUL, decision.feedback)
        assertNull(decision.feedback?.text, "a Harmful Move speaks through its Diagnosis")
        val diagnosis = checkNotNull(decision.diagnosis)
        assertEquals(category, diagnosis.category)
        assertEquals(explanation, diagnosis.explanation)
        assertEquals(explanation, decision.explanation)
        assertEquals(prompt, diagnosis.recoveryPath.prompt)
        assertEquals(Recovery.Unavailable, decision.recovery)

        // The state is the Learner's own: entry · every Move played, never the Expected State.
        assertEquals(played.apply(harmful), harmed.virtual)
        assertEquals(harmed.entry.apply(harmed.history.done), harmed.virtual)
        val undone = harmed.after(undo)
        assertEquals(played, undone.virtual)
        assertNull(undone.lastMoveFeedback)
        assertNull(undone.lastDiagnosis)
        assertNull(checkNotNull(undone.decision).diagnosis)

        // The other clears.
        assertNull(harmed.after(modeChanged(TeachingMode.GUIDE_ME)).lastDiagnosis)
        val asked = harmed.after(resetEvent)
        assertNull(asked.after(checkNotNull(asked.pendingReset).confirm()).lastDiagnosis)
    }

    @Test
    fun `stage 2 insert, 02-04 - a turn at the second step moves the placed pieces`() {
        assertHarmful(
            testMe("lesson-02-04", 1),
            "F",
            ErrorCategory.WRONG_MOVE_OR_DIRECTION,
            Text("msg.diagnosis.wrong-move-or-direction"),
            Text("msg.recovery.undo"),
        )
    }

    @Test
    fun `stage 2 edges, 02-05 - moving the white-green edge while the white-red one waits`() {
        assertHarmful(
            testMe("lesson-02-05", 1),
            "F",
            ErrorCategory.WRONG_PIECE,
            Text("msg.diagnosis.wrong-piece", mapOf("moved" to "white_green", "target" to "white_red")),
            Text("msg.recovery.undo"),
        )
    }

    @Test
    fun `stage 3 insert, 03-01 - R instead of R' moves the cross`() {
        val before = testMe("lesson-03-01", 0)
        assertHarmful(
            before,
            "R",
            ErrorCategory.COMPLETED_LAYER_DISTURBED,
            Text("msg.diagnosis.completed-layer-disturbed", mapOf("layer" to "cross")),
            Text("msg.recovery.guided-recovery"),
        )
        assertEquals(RecoveryPath.GUIDED_RECOVERY, before.after(commit(moves("R").single())).lastDiagnosis?.recoveryPath)
        // The taught R' is on path, with no Diagnosis, even though it lifts the white-red edge.
        val onPath = before.after(commit(moves("R'").single()))
        assertEquals(MoveFeedback.ON_PATH, checkNotNull(onPath.decision).feedback)
        assertNull(onPath.lastDiagnosis)
    }
}
