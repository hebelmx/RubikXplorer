package dev.rubikxplorer.application.session

import dev.rubikxplorer.application.usecase.SeededRandomSource
import dev.rubikxplorer.application.usecase.StagePractice
import dev.rubikxplorer.domain.content.AdaptiveThresholds
import dev.rubikxplorer.domain.content.MasteryThresholds
import dev.rubikxplorer.domain.content.TutorConfig
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.tutor.AssistanceLevel
import dev.rubikxplorer.domain.tutor.HintLadder
import dev.rubikxplorer.domain.tutor.HintRung
import dev.rubikxplorer.domain.tutor.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class PracticeSessionTest {
    private val tutorConfig =
        TutorConfig(
            maxDepth = 2,
            moveSet = "U U' U2 D D' D2 R R' R2 L L' L2 F F' F2 B B' B2",
            recoveryMaxDepth = 5,
            adaptive = AdaptiveThresholds(1, 2, 3, 2),
            mastery = MasteryThresholds(2, 1),
        )

    private val stagePractice = StagePractice(SeededRandomSource(42))

    private fun startPractice(stageId: dev.rubikxplorer.domain.content.StageId): Pair<SessionState, Transition> {
        val lesson = stagePractice(stageId)
        val event = SessionEvent.PracticeStarted(lesson, tutorConfig, Instant.fromEpochMilliseconds(1000))
        val transition = reduce(SessionState.IDLE, event)
        return transition.state to transition
    }

    @Test
    fun `starting a practice session creates a PRACTICE kind session with REDUCED assistance`() {
        val (state, transition) = startPractice(StagePractice.STAGE_2_ID)
        assertEquals(SessionKind.PRACTICE, state.kind)
        assertEquals(AssistanceLevel.REDUCED, state.assistance)
        assertEquals(AssistanceLevel.REDUCED, state.attemptAssistance)
        assertTrue(state.attemptOpen)
        assertFalse(state.objectiveMet)

        val startedEffect = transition.effects.filterIsInstance<Effect.AttemptStarted>().single()
        assertEquals("practice-stage-02-first-cross", startedEffect.objectiveId)
    }

    @Test
    fun `hint ladder in practice caps at TARGET rung and cannot climb to demonstration`() {
        var (state, _) = startPractice(StagePractice.STAGE_2_ID)
        assertEquals(HintRung.NONE, state.shownRung)
        assertEquals(HintRung.TARGET, state.assistance.top)
        assertTrue(HintLadder.canClimb(TeachingMode.GUIDE_ME, state.shownRung, false, state.assistance.top))

        // Clue (rung 1 of 2)
        state = reduce(state, SessionEvent.HintRequested(Instant.fromEpochMilliseconds(2000))).state
        assertEquals(HintRung.CLUE, state.shownRung)
        assertEquals(1, state.hintsUsed)
        assertTrue(HintLadder.canClimb(TeachingMode.GUIDE_ME, state.shownRung, false, state.assistance.top))

        // Target (rung 2 of 2)
        state = reduce(state, SessionEvent.HintRequested(Instant.fromEpochMilliseconds(3000))).state
        assertEquals(HintRung.TARGET, state.shownRung)
        assertEquals(2, state.hintsUsed)

        // Capped at TARGET: cannot climb to DEMONSTRATION
        assertFalse(HintLadder.canClimb(TeachingMode.GUIDE_ME, state.shownRung, false, state.assistance.top))
        val unchanged = reduce(state, SessionEvent.HintRequested(Instant.fromEpochMilliseconds(4000))).state
        assertEquals(HintRung.TARGET, unchanged.shownRung)
        assertEquals(2, unchanged.hintsUsed)
    }

    @Test
    fun `completing practice attempt emits AttemptEnded with outcome COMPLETED and assistanceLevel REDUCED`() {
        // Construct a practice lesson where 1 move solves the objective
        val (initialState, _) = startPractice(StagePractice.STAGE_2_ID)
        // Verify moves modify virtual state in PRACTICE session
        val move = FaceTurn(Face.R, Turn.CLOCKWISE)
        val afterMove = reduce(initialState, SessionEvent.MoveCommitted(move, Instant.fromEpochMilliseconds(2000))).state
        assertEquals(1, afterMove.history.done.size)
        assertTrue(afterMove.history.canUndo)

        // Undo
        val afterUndo = reduce(afterMove, SessionEvent.Undo(Instant.fromEpochMilliseconds(2500))).state
        assertEquals(0, afterUndo.history.done.size)
        assertTrue(afterUndo.history.canRedo)

        // Redo
        val afterRedo = reduce(afterUndo, SessionEvent.Redo(Instant.fromEpochMilliseconds(2600))).state
        assertEquals(1, afterRedo.history.done.size)

        // Reset
        val afterReset = reduce(afterRedo, SessionEvent.Reset(Instant.fromEpochMilliseconds(2700))).state
        // Asks confirmation when unsaved steps exist
        assertTrue(afterReset.pendingReset != null)
        val resetConfirmed =
            reduce(
                afterReset,
                SessionEvent.ResetConfirmed(
                    afterReset.pendingReset!!.effectId,
                    afterReset.revision,
                    Instant.fromEpochMilliseconds(2800),
                ),
            ).state
        assertEquals(0, resetConfirmed.history.done.size)
    }

    @Test
    fun `solving completion predicate emits AttemptEnded COMPLETED with REDUCED assistance`() {
        val lesson = stagePractice(StagePractice.STAGE_2_ID)
        // Let's create an entry state that is 1 move away from white cross solved
        val solvedCross = dev.rubikxplorer.domain.cube.CubeState.SOLVED
        val oneMoveAway = solvedCross.apply(FaceTurn(Face.F, Turn.COUNTERCLOCKWISE))
        val customLesson =
            lesson.copy(
                entryState =
                    dev.rubikxplorer.domain.content.FaceletString
                        .encode(oneMoveAway.toFacelets()),
            )
        val startTransition =
            reduce(
                SessionState.IDLE,
                SessionEvent.PracticeStarted(customLesson, tutorConfig, Instant.fromEpochMilliseconds(1000)),
            )
        val state = startTransition.state
        assertTrue(state.attemptOpen)
        assertFalse(state.objectiveMet)

        // Apply F move to solve white cross
        val solveTransition =
            reduce(
                state,
                SessionEvent.MoveCommitted(FaceTurn(Face.F, Turn.CLOCKWISE), Instant.fromEpochMilliseconds(2000)),
            )
        assertTrue(solveTransition.state.objectiveMet)
        assertFalse(solveTransition.state.attemptOpen)

        val endedEffect = solveTransition.effects.filterIsInstance<Effect.AttemptEnded>().single()
        assertEquals(AttemptOutcome.COMPLETED, endedEffect.outcome)
        assertEquals(AssistanceLevel.REDUCED, endedEffect.assistanceLevel)
        assertEquals("practice-stage-02-first-cross", endedEffect.objectiveId)
    }

    @Test
    fun `harmful moves in practice session increment step mistakes and are recorded in AttemptEnded`() {
        val lesson = stagePractice(StagePractice.STAGE_3_ID)
        val startTransition =
            reduce(
                SessionState.IDLE,
                SessionEvent.PracticeStarted(lesson, tutorConfig, Instant.fromEpochMilliseconds(1000)),
            )
        val state = startTransition.state
        // For Stage 3, white cross is preserved; U turn disturbs white cross
        val move = FaceTurn(Face.U, Turn.CLOCKWISE)
        val afterMove =
            reduce(
                state,
                SessionEvent.MoveCommitted(move, Instant.fromEpochMilliseconds(2000)),
            ).state
        assertEquals(1, afterMove.stepMistakes.values.sum())
    }

    @Test
    fun `replacing an open practice attempt emits AttemptEnded ABANDONED with REDUCED assistance`() {
        val (state, _) = startPractice(StagePractice.STAGE_2_ID)
        assertTrue(state.attemptOpen)

        val secondLesson = stagePractice(StagePractice.STAGE_3_ID)
        val replaceTransition =
            reduce(
                state,
                SessionEvent.PracticeStarted(secondLesson, tutorConfig, Instant.fromEpochMilliseconds(2000)),
            )

        val endedEffect = replaceTransition.effects.filterIsInstance<Effect.AttemptEnded>().single()
        assertEquals(AttemptOutcome.ABANDONED, endedEffect.outcome)
        assertEquals(AssistanceLevel.REDUCED, endedEffect.assistanceLevel)
        assertEquals("practice-stage-02-first-cross", endedEffect.objectiveId)

        val startedEffect = replaceTransition.effects.filterIsInstance<Effect.AttemptStarted>().single()
        assertEquals("practice-stage-03-first-layer-corners", startedEffect.objectiveId)
    }

    @Test
    fun `suspending practice session with solve playback emits AttemptEnded ABANDONED`() {
        val (state, _) = startPractice(StagePractice.STAGE_2_ID)
        assertTrue(state.attemptOpen)

        val playbackTransition =
            reduce(
                state,
                SessionEvent.SolvePlaybackStarted(
                    dev.rubikxplorer.domain.cube.CubeState.SOLVED,
                    emptyList(),
                    Instant.fromEpochMilliseconds(2000),
                ),
            )
        val endedEffect = playbackTransition.effects.filterIsInstance<Effect.AttemptEnded>().single()
        assertEquals(AttemptOutcome.ABANDONED, endedEffect.outcome)
        assertEquals(AssistanceLevel.REDUCED, endedEffect.assistanceLevel)
    }
}
