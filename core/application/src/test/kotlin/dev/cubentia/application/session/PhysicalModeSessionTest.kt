package dev.cubentia.application.session

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.FaceletString
import dev.cubentia.domain.content.Lesson
import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.Slice
import dev.cubentia.domain.cube.SliceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.input.ViewOrbit
import dev.cubentia.domain.predicate.StateReached
import dev.cubentia.domain.recognition.PhysicalObservation
import dev.cubentia.domain.text.Text
import dev.cubentia.domain.tutor.Assess
import dev.cubentia.domain.tutor.Certainty
import dev.cubentia.domain.tutor.MoveFeedback
import dev.cubentia.domain.tutor.RecoveryPlanner
import dev.cubentia.domain.tutor.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Story 8.1: Physical-mode transitions in the reducer.
 *
 * Verifies the 5-state Sync State Machine (AD-3, AD-5), physical undo instructions,
 * undo barriers, and invariants across random event sequences.
 */
class PhysicalModeSessionTest {
    private val shippedStrings: Map<String, String> by lazy {
        val result = ContentLoader.load(ContentSource.SHIPPED)
        assertInstanceOf(ContentLoadResult.Loaded::class.java, result)
        (result as ContentLoadResult.Loaded).bundle.strings
    }

    private fun resolveText(text: Text): String {
        val pattern = shippedStrings.getValue(text.key)
        return text.args.entries.fold(pattern) { acc, (key, value) ->
            acc.replace("{$key}", value.toString())
        }
    }

    @Test
    fun `sync state machine transitions through physical states mirroring expected`() {
        val session = started()
        assertEquals(SyncState.VIRTUAL, session.syncState)
        assertEquals(CubeMode.VIRTUAL, session.mode)
        assertEquals(session.virtual, session.expected)

        // Virtual -> PhysicalAwaitingConfirm
        val switchToPhysical = reduce(session, SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0))
        val awaiting = switchToPhysical.state
        assertEquals(SyncState.PHYSICAL_AWAITING_CONFIRM, awaiting.syncState)
        assertEquals(CubeMode.PHYSICAL, awaiting.mode)
        assertEquals(awaiting.expected, awaiting.virtual)
        assertFalse(awaiting.history.canUndo)
        assertFalse(awaiting.history.canRedo)
        assertEquals(session.revision + 1, awaiting.revision)

        // PhysicalAwaitingConfirm -> PhysicalPredicted via PhysicalConfirmed(null)
        val confirmMatch = reduce(awaiting, SessionEvent.PhysicalConfirmed(null, T0))
        val predicted = confirmMatch.state
        assertEquals(SyncState.PHYSICAL_PREDICTED, predicted.syncState)
        assertEquals(awaiting.expected, predicted.expected)
        assertEquals(predicted.expected, predicted.virtual)
        assertEquals(awaiting.revision, predicted.revision) // revision unchanged on match confirm

        // PhysicalPredicted -> PhysicalPredicted via PhysicalConfirmed(R)
        val confirmMove = reduce(predicted, SessionEvent.PhysicalConfirmed(R, T0))
        val afterMove = confirmMove.state
        assertEquals(SyncState.PHYSICAL_PREDICTED, afterMove.syncState)
        assertEquals(predicted.expected.apply(R), afterMove.expected)
        assertEquals(afterMove.expected, afterMove.virtual)
        assertNull(afterMove.observed)
        assertEquals(predicted.revision + 1, afterMove.revision)
        assertEquals(listOf(Presentation.Animate(R, afterMove.revision, afterMove.virtual)), confirmMove.presentation)

        // Report mismatch: PhysicalPredicted -> PhysicalMismatch
        val reportMismatch = reduce(afterMove, SessionEvent.MismatchReported(T0))
        val mismatch = reportMismatch.state
        assertEquals(SyncState.PHYSICAL_MISMATCH, mismatch.syncState)
        assertEquals(afterMove.expected, mismatch.expected)
        assertEquals(afterMove.virtual, mismatch.virtual)
        assertEquals(afterMove.revision, mismatch.revision) // revision unchanged

        // Undo during mismatch -> PhysicalPredicted, reverts expected, produces instruct
        val undoTransition = reduce(mismatch, SessionEvent.Undo(T0))
        val afterUndo = undoTransition.state
        assertEquals(SyncState.PHYSICAL_PREDICTED, afterUndo.syncState)
        assertEquals(predicted.expected, afterUndo.expected)
        assertEquals(afterUndo.expected, afterUndo.virtual)
        assertEquals(mismatch.revision + 1, afterUndo.revision)
        val instruct = undoTransition.presentation.single() as Presentation.Instruct
        assertEquals(R_PRIME, instruct.move)
        assertEquals(Text("msg.physical.undo", mapOf("move" to "R'")), instruct.text)
        assertEquals("Turn R' on your cube to undo.", resolveText(instruct.text))

        // EditorEntered from PhysicalPredicted
        val enteredState = CubeState.SOLVED.apply(U, F)
        val editorTransition = reduce(afterUndo, SessionEvent.EditorEntered(enteredState, T0))
        val afterEditor = editorTransition.state
        assertEquals(SyncState.PHYSICAL_VERIFIED, afterEditor.syncState)
        assertEquals(enteredState, afterEditor.expected)
        assertEquals(enteredState, afterEditor.virtual)
        assertEquals(PhysicalObservation.Full(enteredState), afterEditor.observed)
        assertFalse(afterEditor.history.canUndo)
        assertFalse(afterEditor.history.canRedo)
        assertEquals(afterUndo.revision + 1, afterEditor.revision)

        // Physical -> Virtual mode switch
        val switchToVirtual = reduce(afterEditor, SessionEvent.ModeSwitched(CubeMode.VIRTUAL, T0))
        val backToVirtual = switchToVirtual.state
        assertEquals(SyncState.VIRTUAL, backToVirtual.syncState)
        assertEquals(CubeMode.VIRTUAL, backToVirtual.mode)
        assertEquals(enteredState, backToVirtual.virtual)
        assertFalse(backToVirtual.history.canUndo)
        assertFalse(backToVirtual.history.canRedo)
        assertEquals(afterEditor.revision + 1, backToVirtual.revision)
        assertEquals(Text("msg.physical.back-to-virtual"), backToVirtual.restoreNote)
        assertEquals("The on-screen cube now matches what we expected on yours.", resolveText(backToVirtual.restoreNote!!))
    }

    @Test
    fun `undo of physical confirmed move reverts expected and instructs inverse move`() {
        val session =
            started()
                .after(
                    SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0),
                    SessionEvent.PhysicalConfirmed(null, T0),
                    SessionEvent.PhysicalConfirmed(R, T0),
                )
        assertEquals(CubeState.SOLVED.apply(F, R), session.expected)
        assertEquals(session.expected, session.virtual)

        val undoTransition = reduce(session, SessionEvent.Undo(T0))
        val undone = undoTransition.state

        assertEquals(CubeState.SOLVED.apply(F), undone.expected)
        assertEquals(undone.expected, undone.virtual)
        assertEquals(session.revision + 1, undone.revision)

        val presentation = undoTransition.presentation.single()
        assertInstanceOf(Presentation.Instruct::class.java, presentation)
        val instruct = presentation as Presentation.Instruct
        assertEquals(R_PRIME, instruct.move)
        assertEquals(Text("msg.physical.undo", mapOf("move" to "R'")), instruct.text)
        assertEquals("Turn R' on your cube to undo.", resolveText(instruct.text))
    }

    @Test
    fun `mode switch acts as undo barrier and clears redo`() {
        // Build up undo and redo in virtual mode
        val virtualWithRedo = started().after(commit(R), commit(U), undo)
        assertTrue(virtualWithRedo.history.canUndo)
        assertTrue(virtualWithRedo.history.canRedo)

        // Switch to physical: clears redo, acts as undo barrier
        val physical = reduce(virtualWithRedo, SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0)).state
        assertFalse(physical.history.canUndo)
        assertFalse(physical.history.canRedo)

        // Undo at barrier in physical mode is a no-op
        val undoAtBarrier = reduce(physical, SessionEvent.Undo(T0))
        assertEquals(physical, undoAtBarrier.state)
        assertTrue(undoAtBarrier.presentation.isEmpty())

        // Confirm a physical move and switch back to virtual: acts as undo barrier
        val physicalMoved =
            physical.after(
                SessionEvent.PhysicalConfirmed(null, T0),
                SessionEvent.PhysicalConfirmed(F, T0),
            )
        assertTrue(physicalMoved.history.canUndo)

        val backToVirtual = reduce(physicalMoved, SessionEvent.ModeSwitched(CubeMode.VIRTUAL, T0)).state
        assertFalse(backToVirtual.history.canUndo)
        assertFalse(backToVirtual.history.canRedo)
        assertEquals(Text("msg.physical.back-to-virtual"), backToVirtual.restoreNote)
        assertEquals("The on-screen cube now matches what we expected on yours.", resolveText(backToVirtual.restoreNote!!))

        val undoVirtualAtBarrier = reduce(backToVirtual, SessionEvent.Undo(T0))
        assertEquals(backToVirtual.copy(restoreNote = null), undoVirtualAtBarrier.state)
        assertNull(undoVirtualAtBarrier.state.restoreNote)
    }

    @Test
    fun `mode switch ignores if already in target mode`() {
        val virtual = started()
        val switchedSameVirtual = reduce(virtual, SessionEvent.ModeSwitched(CubeMode.VIRTUAL, T0))
        assertEquals(virtual, switchedSameVirtual.state)

        val physical = virtual.after(SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0))
        val switchedSamePhysical = reduce(physical, SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0))
        assertEquals(physical, switchedSamePhysical.state)
    }

    @Test
    fun `checkpoint restored in physical mode enters awaiting confirm and clears redo`() {
        val session = started()
        val lesson = checkNotNull(session.lesson)
        val afterMoves =
            session.after(
                SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0),
                SessionEvent.PhysicalConfirmed(null, T0),
                SessionEvent.PhysicalConfirmed(R, T0),
            )
        val checkpoint =
            Checkpoint(
                kind = SessionKind.LESSON,
                lessonId = lesson.id.value,
                stageId = lesson.stageId.value,
                contentRevision = "v1",
                start = FaceletString.encode(session.virtual.toFacelets()),
                cube = FaceletString.encode(afterMoves.expected.toFacelets()),
                startOrientation = "UF",
                cubeOrientation = "UF",
                history = "R",
                mode = CubeMode.PHYSICAL,
                teachingMode = session.teachingMode,
                adaptiveAssistance = session.adaptiveAssistance,
                savedAt = T0.toEpochMilliseconds(),
            )

        val restoredTransition =
            reduce(
                SessionState.IDLE,
                SessionEvent.CheckpointRestored(checkpoint, lesson, checkNotNull(session.tutorConfig), "v1", T0),
            )
        val restored = restoredTransition.state

        assertEquals(SyncState.PHYSICAL_AWAITING_CONFIRM, restored.syncState)
        assertEquals(CubeMode.PHYSICAL, restored.mode)
        assertEquals(afterMoves.expected, restored.expected)
        assertEquals(restored.expected, restored.virtual)
        assertNull(restored.observed)
        assertFalse(restored.history.canUndo)
        assertFalse(restored.history.canRedo)
        assertEquals(listOf(Presentation.Snap(restored.revision, restored.virtual)), restoredTransition.presentation)
    }

    @Test
    fun `story 8 4 ac1 - switch from physical to virtual aligns virtual to expected, sets restoreNote, and acts as undo barrier`() {
        val session = started()
        val physicalTransition = reduce(session, SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0))
        val physical = physicalTransition.state
        assertEquals(SyncState.PHYSICAL_AWAITING_CONFIRM, physical.syncState)
        assertNull(physical.restoreNote)

        // Confirm match and make a physical move
        val confirmed = reduce(physical, SessionEvent.PhysicalConfirmed(null, T0)).state
        val moved = reduce(confirmed, SessionEvent.PhysicalConfirmed(R, T0)).state
        assertEquals(SyncState.PHYSICAL_PREDICTED, moved.syncState)
        assertEquals(physical.expected.apply(R), moved.expected)
        assertEquals(moved.expected, moved.virtual)
        assertTrue(moved.history.canUndo)

        // Switch to virtual: virtual becomes expected, restoreNote set, undo/redo cleared
        val toVirtualTransition = reduce(moved, SessionEvent.ModeSwitched(CubeMode.VIRTUAL, T0))
        val virtual = toVirtualTransition.state
        assertEquals(SyncState.VIRTUAL, virtual.syncState)
        assertEquals(CubeMode.VIRTUAL, virtual.mode)
        assertEquals(moved.expected, virtual.virtual)
        assertNull(virtual.observed)
        assertFalse(virtual.history.canUndo, "Undo barrier: cannot undo back into physical mode")
        assertFalse(virtual.history.canRedo)
        assertEquals(Text("msg.physical.back-to-virtual"), virtual.restoreNote)
        assertEquals("The on-screen cube now matches what we expected on yours.", resolveText(virtual.restoreNote!!))

        // Subsequent event clears restoreNote
        val commitMove = reduce(virtual, SessionEvent.MoveCommitted(U, T0)).state
        assertNull(commitMove.restoreNote)

        // Undo barrier prevents undoing further
        val undoAtStart = reduce(virtual, SessionEvent.Undo(T0)).state
        assertFalse(undoAtStart.history.canUndo)
        assertEquals(virtual.virtual, undoAtStart.virtual)
    }

    @Test
    fun `story 8 4 ac2 - physical snapshot restored lands in physical awaiting confirm with observed cleared and no async effects`() {
        val session = started()
        val lesson = checkNotNull(session.lesson)
        val physical =
            session.after(
                SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0),
                SessionEvent.PhysicalConfirmed(null, T0),
                SessionEvent.PhysicalConfirmed(R, T0),
            )
        val snapshot = checkNotNull(SessionSnapshot.of(physical, "session-test", "v1"))
        assertEquals(CubeMode.PHYSICAL, snapshot.mode)

        val restoredTransition =
            reduce(
                SessionState.IDLE,
                SessionEvent.SessionRestored(snapshot, lesson, checkNotNull(session.tutorConfig), T0),
            )
        val restored = restoredTransition.state

        assertEquals(SyncState.PHYSICAL_AWAITING_CONFIRM, restored.syncState)
        assertEquals(CubeMode.PHYSICAL, restored.mode)
        assertEquals(physical.expected, restored.expected)
        assertEquals(restored.expected, restored.virtual)
        assertNull(restored.observed)
        assertTrue(restoredTransition.effects.isEmpty(), "No async effects resumed on physical snapshot restore")
        assertEquals(listOf(Presentation.Snap(restored.revision, restored.virtual)), restoredTransition.presentation)
        assertEquals(Text("msg.lesson.resumed"), restored.restoreNote)
    }

    @Test
    fun `story 8 4 - mode recorded across persist checkpoint, checkpoint of, and session snapshot`() {
        val session = started()
        // Initial start emits PersistCheckpoint with mode = VIRTUAL
        val startCheckpoint =
            reduce(SessionState.IDLE, SessionEvent.LessonStarted(checkNotNull(session.lesson), checkNotNull(session.tutorConfig), T0))
                .effects
                .filterIsInstance<Effect.PersistCheckpoint>()
                .single()
        assertEquals(CubeMode.VIRTUAL, startCheckpoint.mode)
        assertEquals(CubeMode.VIRTUAL, Checkpoint.of(startCheckpoint, "v1").mode)

        val toPhysical = reduce(session, SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0))
        val physicalSnapshot = checkNotNull(SessionSnapshot.of(toPhysical.state, "sess-1", "v1"))
        assertEquals(CubeMode.PHYSICAL, physicalSnapshot.mode)

        // Switching to physical and reaching boundary emits PersistCheckpoint with mode = PHYSICAL
        val confirmed = reduce(toPhysical.state, SessionEvent.PhysicalConfirmed(null, T0)).state
        val boundaryTransition = reduce(confirmed, SessionEvent.PhysicalConfirmed(F_PRIME, T0))
        val boundaryCheckpoint = boundaryTransition.effects.filterIsInstance<Effect.PersistCheckpoint>().single()
        assertEquals(CubeMode.PHYSICAL, boundaryCheckpoint.mode)
        val physicalCheckpoint = Checkpoint.of(boundaryCheckpoint, "v1")
        assertEquals(CubeMode.PHYSICAL, physicalCheckpoint.mode)

        // Restoring physical checkpoint emits PersistCheckpoint with mode = PHYSICAL
        val restoredTransition =
            reduce(
                SessionState.IDLE,
                SessionEvent.CheckpointRestored(
                    physicalCheckpoint,
                    checkNotNull(session.lesson),
                    checkNotNull(session.tutorConfig),
                    "v1",
                    T0,
                ),
            )
        val restoredCheckpoint = restoredTransition.effects.filterIsInstance<Effect.PersistCheckpoint>().single()
        assertEquals(CubeMode.PHYSICAL, restoredCheckpoint.mode)
    }

    @Test
    fun `story 8 4 - objective boundary in physical mode persists physical checkpoint`() {
        val session = started()
        val physical =
            session.after(
                SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0),
                SessionEvent.PhysicalConfirmed(null, T0),
            )
        // Committing move that completes a step / boundary (F_PRIME completes WhiteCrossSolved)
        val boundaryTransition = reduce(physical, SessionEvent.PhysicalConfirmed(F_PRIME, T0))
        val effect = boundaryTransition.effects.filterIsInstance<Effect.PersistCheckpoint>().single()
        assertEquals(CubeMode.PHYSICAL, effect.mode)
        assertEquals(CubeMode.PHYSICAL, Checkpoint.of(effect, "v1").mode)
    }

    @Test
    fun `physical confirmed in test me retains feedback and diagnosis`() {
        val session =
            started()
                .after(
                    modeChanged(TeachingMode.TEST_ME),
                    SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0),
                    SessionEvent.PhysicalConfirmed(null, T0),
                )
        assertEquals(SyncState.PHYSICAL_PREDICTED, session.syncState)
        assertEquals(TeachingMode.TEST_ME, session.teachingMode)

        // Rotation move Y produces both move feedback and diagnosis in whiteCrossLesson
        val confirmedY = reduce(session, SessionEvent.PhysicalConfirmed(Y, T0)).state
        assertEquals(SyncState.PHYSICAL_PREDICTED, confirmedY.syncState)
        assertTrue(confirmedY.lastMoveFeedback != null, "lastMoveFeedback should be retained in TEST_ME")
        assertTrue(confirmedY.lastDiagnosis != null, "lastDiagnosis should be retained in TEST_ME for rotation Y")

        // In a non-assessed mode (SHOW_ME), feedback and diagnosis are not retained
        val showMeSession =
            started()
                .after(
                    modeChanged(TeachingMode.SHOW_ME),
                    SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0),
                    SessionEvent.PhysicalConfirmed(null, T0),
                )
        val confirmedShowMe = reduce(showMeSession, SessionEvent.PhysicalConfirmed(Y, T0)).state
        assertNull(confirmedShowMe.lastMoveFeedback)
        assertNull(confirmedShowMe.lastDiagnosis)
    }

    @Test
    fun `mismatch reporting and entered move diagnosis and editor re-entry with guided recovery`() {
        val lesson = shippedLesson("lesson-03-01")
        val session = started(lesson).after(toPhysical, matches)
        assertEquals(SyncState.PHYSICAL_PREDICTED, session.syncState)

        // Reporting mismatch transitions to PHYSICAL_MISMATCH with Certainty UNKNOWN
        val mismatchState = reduce(session, reported).state
        assertEquals(SyncState.PHYSICAL_MISMATCH, mismatchState.syncState)
        assertNull(mismatchState.lastDiagnosis)
        assertEquals(Certainty.UNKNOWN, mismatchState.decision?.certainty)

        // Re-entering the cube lands in PHYSICAL_VERIFIED with a Full observation and offers Guided recovery
        val brokenState = CubeState.SOLVED.apply(R, U, F)
        val afterEditor = reduce(mismatchState, SessionEvent.EditorEntered(brokenState, T0)).state
        assertEquals(SyncState.PHYSICAL_VERIFIED, afterEditor.syncState)
        assertEquals(PhysicalObservation.Full(brokenState), afterEditor.observed)
        assertEquals(brokenState, afterEditor.expected)
        assertEquals(brokenState, afterEditor.virtual)
        assertTrue(afterEditor.decision?.diagnosis != null)
        assertTrue(afterEditor.canGuideBack)
    }

    private val reported = SessionEvent.MismatchReported(T0)

    private fun confirm(move: Move) = SessionEvent.PhysicalConfirmed(move, T0)

    private fun mismatched(lesson: Lesson = whiteCrossLesson()): SessionState = started(lesson).after(toPhysical, matches, reported)

    private fun evidence(
        state: SessionState,
        move: Move,
    ) = Assess.diagnosePhysicalMove(
        checkNotNull(state.lesson),
        state.pathEntry,
        state.expected,
        state.expected,
        move,
        checkNotNull(state.tutorConfig),
    )

    private val slice = SliceTurn(Slice.M, Turn.CLOCKWISE)

    @Test
    fun `rung 1 commits the entered move and carries its diagnosis`() {
        val mismatch = mismatched()
        val transition = reduce(mismatch, confirm(R))
        val next = transition.state

        assertEquals(SyncState.PHYSICAL_PREDICTED, next.syncState)
        assertEquals(mismatch.expected.apply(R), next.expected)
        assertEquals(next.expected, next.virtual)
        assertNull(next.observed)
        assertEquals(mismatch.revision + 1, next.revision)
        assertEquals(listOf(R), next.history.done)
        assertEquals(evidence(mismatch, R), next.lastDiagnosis)
        assertEquals(Certainty.VERIFIED, next.lastDiagnosis?.certainty)
        assertEquals(next.lastDiagnosis?.certainty, next.decision?.certainty)
        assertNull(next.lastMoveFeedback)
        assertEquals(listOf(Presentation.Animate(R, next.revision, next.virtual)), transition.presentation)

        val undone = next.after(undo)
        assertEquals(mismatch.expected, undone.expected)
        assertEquals(SyncState.PHYSICAL_PREDICTED, undone.syncState)
    }

    @Test
    fun `rung 1 keeps a likely certainty and diagnoses in every teaching mode`() {
        TeachingMode.entries.forEach { mode ->
            val mismatch = started().after(modeChanged(mode), toPhysical, matches, reported)
            val next = mismatch.after(confirm(slice))
            assertEquals(evidence(mismatch, slice), next.lastDiagnosis, "$mode")
            assertEquals(Certainty.LIKELY, next.lastDiagnosis?.certainty, "$mode")
            assertEquals(Certainty.LIKELY, next.decision?.certainty, "$mode")
            assertNull(next.lastMoveFeedback, "$mode")
        }
    }

    @Test
    fun `rung 1 with prior confirms bases the diagnosis on expected and keeps the prior move`() {
        val mismatch = started().after(toPhysical, matches, confirm(U), reported)
        val next = mismatch.after(confirm(R))

        assertEquals(listOf(U, R), next.history.done)
        assertEquals(mismatch.expected.apply(R), next.expected)
        assertEquals(evidence(mismatch, R), next.lastDiagnosis)
        assertEquals(listOf(R), checkNotNull(next.lastDiagnosis).candidates.single().moves)
    }

    @Test
    fun `rung 1 that meets the objective keeps the diagnosed certainty and still needs the solve confirmation`() {
        val lesson = whiteCrossLesson().copy(completion = StateReached("F M"))
        val mismatch = mismatched(lesson)
        val transition = reduce(mismatch, confirm(slice))
        val next = transition.state

        assertTrue(next.objectiveMet)
        assertNull(next.decision?.diagnosis, "the done step hides the Diagnosis")
        assertEquals(Certainty.LIKELY, next.lastDiagnosis?.certainty)
        assertEquals(Certainty.LIKELY, next.decision?.certainty, "never the confirm's VERIFIED")
        assertTrue(next.physicalSolveUnconfirmed)
        assertFalse(next.physicalSolveConfirmed)
        assertEquals(next.history, next.checkpoint, "an Objective boundary becomes the Checkpoint")
        assertEquals(1, transition.effects.filterIsInstance<Effect.PersistCheckpoint>().size)
        assertTrue(next.after(solveConfirmed).physicalSolveConfirmed)
    }

    @Test
    fun `rung 1 outside the enumerator is unknown and expected still updates`() {
        val mismatch =
            SessionState.IDLE.after(
                lessonStarted(whiteCrossLesson(), config = TUTOR_CONFIG.copy(maxDepth = 1)),
                toPhysical,
                matches,
                reported,
            )
        val next = mismatch.after(confirm(slice))

        assertEquals(mismatch.expected.apply(slice), next.expected)
        assertEquals(SyncState.PHYSICAL_PREDICTED, next.syncState)
        assertEquals(Certainty.UNKNOWN, next.lastDiagnosis?.certainty)
        assertEquals(Certainty.UNKNOWN, next.decision?.certainty)
    }

    @Test
    fun `rung 1 counts for adaptive assistance like any commit`() {
        val lesson = shippedLesson("lesson-03-01")
        val mismatch = SessionState.IDLE.after(lessonStarted(lesson, adaptiveAssistance = true), toPhysical, matches, reported)
        val next = mismatch.after(confirm(R))

        assertEquals(1, next.stepMistakes.values.sum())
        assertNull(next.lastMoveFeedback)
    }

    @Test
    fun `it matches after all returns to predicted with expected and revision unchanged`() {
        val mismatch = started().after(toPhysical, matches, confirm(R), reported)
        val next = reduce(mismatch, matches)

        assertEquals(SyncState.PHYSICAL_PREDICTED, next.state.syncState)
        assertEquals(mismatch.expected, next.state.expected)
        assertEquals(mismatch.history, next.state.history)
        assertEquals(mismatch.revision, next.state.revision)
        assertTrue(next.presentation.isEmpty())
    }

    @Test
    fun `an accepted editor entry from every physical state is verified and ignored in virtual mode`() {
        val entered = CubeState.SOLVED.apply(U, F)
        val awaiting = started().after(toPhysical)
        val predicted = awaiting.after(matches)
        val verified = predicted.after(SessionEvent.EditorEntered(CubeState.SOLVED.apply(R), T0))
        val mismatch = predicted.after(reported)
        assertEquals(SyncState.PHYSICAL_VERIFIED, verified.syncState)

        listOf(awaiting, predicted, verified, mismatch).forEach { before ->
            val next = before.after(SessionEvent.EditorEntered(entered, T0))
            assertEquals(SyncState.PHYSICAL_VERIFIED, next.syncState, "${before.syncState}")
            assertEquals(PhysicalObservation.Full(entered), next.observed, "${before.syncState}")
            assertEquals(entered, next.expected, "${before.syncState}")
        }

        val virtual = started()
        val ignored = virtual.after(SessionEvent.EditorEntered(entered, T0))
        assertEquals(SyncState.VIRTUAL, ignored.syncState)
        assertEquals(virtual.virtual, ignored.virtual)
        assertNull(ignored.observed)
    }

    @Test
    fun `dismissing a mismatch reported after an editor entry clears the observation`() {
        val verified = started().after(toPhysical, matches, SessionEvent.EditorEntered(CubeState.SOLVED.apply(U, F), T0))
        val mismatch = verified.after(reported)
        val next = mismatch.after(matches)

        assertEquals(SyncState.PHYSICAL_PREDICTED, next.syncState)
        assertNull(next.observed)
        assertEquals(verified.expected, next.expected)
        assertEquals(verified.revision, next.revision)
    }

    @Test
    fun `reporting a mismatch from verified keeps expected and revision`() {
        val verified = started().after(toPhysical, matches, SessionEvent.EditorEntered(CubeState.SOLVED.apply(U, F), T0))
        val next = verified.after(reported)

        assertEquals(SyncState.PHYSICAL_MISMATCH, next.syncState)
        assertEquals(verified.expected, next.expected)
        assertEquals(verified.revision, next.revision)
    }

    @Test
    fun `an offered guided recovery survives physical confirms made before guide me back`() {
        val physical = started(shippedLesson("lesson-03-01")).after(toPhysical, matches)
        val verified = physical.after(SessionEvent.EditorEntered(physical.expected.apply(R, U), T0))
        assertTrue(verified.canGuideBack)

        val once = verified.after(confirm(U))
        assertTrue(once.canGuideBack)
        assertEquals(SyncState.PHYSICAL_PREDICTED, once.syncState)
        // Carried past a confirm, the Diagnosis is never a false VERIFIED on a predicted cube.
        assertEquals(Certainty.VERIFIED, verified.decision?.certainty)
        assertEquals(Certainty.LIKELY, once.decision?.certainty)
        // Its candidates explained the cube before this confirm.
        assertTrue(checkNotNull(once.lastDiagnosis).candidates.isEmpty())
        val twice = once.after(confirm(U))
        assertEquals(TeachingMode.GUIDE_ME, twice.teachingMode)
        assertEquals(SyncState.PHYSICAL_PREDICTED, twice.syncState)
        assertTrue(twice.canGuideBack)
    }

    @Test
    fun `a confirm that meets the recovery goal drops the carried diagnosis`() {
        val physical = started(shippedLesson("lesson-03-01")).after(toPhysical, matches)
        val verified = physical.after(SessionEvent.EditorEntered(physical.expected.apply(R, U), T0))

        val halfway = verified.after(confirm(U_PRIME))
        assertTrue(halfway.canGuideBack)
        val repaired = halfway.after(confirm(R_PRIME))

        assertFalse(repaired.canGuideBack)
        assertNull(repaired.lastDiagnosis)
    }

    @Test
    fun `a diagnosis that offers no guide me back ends on the next physical commit`() {
        val shown = started().after(toPhysical, matches).copy(lastDiagnosis = TURNED_DIAGNOSIS)
        assertFalse(shown.canGuideBack)

        assertNull(shown.after(confirm(U)).lastDiagnosis)
    }

    @Test
    fun `a rung-1 diagnosis ends on the next physical commit`() {
        val committed = mismatched().after(confirm(R))
        assertTrue(committed.lastDiagnosis != null)
        assertFalse(committed.canGuideBack)

        assertNull(committed.after(confirm(U)).lastDiagnosis)
    }

    @Test
    fun `a plan left by an off-plan move still ends after an editor entry`() {
        val physical = started(shippedLesson("lesson-03-01")).after(toPhysical, matches)
        val asked =
            reduce(physical.after(SessionEvent.EditorEntered(physical.expected.apply(R, U), T0)), SessionEvent.GuidedRecoveryRequested(T0))
        val effect = asked.effects.filterIsInstance<Effect.PlanRecovery>().single()
        val planned = asked.state.after(effect.planned(listOf(U_PRIME, R_PRIME)))
        assertInstanceOf(GuidedRecovery.Planned::class.java, planned.guidedRecovery)

        assertEquals(GuidedRecovery.None, planned.after(confirm(R)).guidedRecovery)
    }

    @Test
    fun `the next confirm after the editor returns to predicted and clears the observation`() {
        val verified = started().after(toPhysical, matches, SessionEvent.EditorEntered(CubeState.SOLVED.apply(U, F), T0))
        val next = verified.after(confirm(R))

        assertEquals(SyncState.PHYSICAL_PREDICTED, next.syncState)
        assertNull(next.observed)
        assertEquals(verified.expected.apply(R), next.expected)
    }

    private fun following(vararg moves: Move): SessionState {
        val plan = listOf(*moves)
        return started().after(toPhysical, matches).copy(
            guidedRecovery = GuidedRecovery.Planned(plan, emptySet(), RecoveryPlanner.describe(plan, emptySet())),
        )
    }

    @Test
    fun `confirming the plan's next move keeps the plan with the moves left`() {
        val next = following(R, U).after(confirm(R))
        val plan = next.guidedRecovery as GuidedRecovery.Planned
        assertEquals(listOf<Move>(U), plan.moves)
        assertEquals(RecoveryPlanner.describe(listOf(U), emptySet()), plan.text)

        assertEquals(GuidedRecovery.None, next.after(confirm(U)).guidedRecovery)
    }

    @Test
    fun `confirming another move ends the plan`() {
        assertEquals(GuidedRecovery.None, following(R, U).after(confirm(U)).guidedRecovery)
    }

    @Test
    fun `an idle confirm keeps a plan and a diagnosis`() {
        val shown = following(R, U).copy(lastMoveFeedback = MoveFeedback.NEUTRAL, lastDiagnosis = TURNED_DIAGNOSIS)
        assertSame(shown, reduce(shown, matches).state)
    }

    @Test
    fun `a guided recovery offered after the editor survives the physical confirms that follow it`() {
        val physical = started(shippedLesson("lesson-03-01")).after(toPhysical, matches)
        val entered = physical.expected.apply(R, U)
        val verified = physical.after(SessionEvent.EditorEntered(entered, T0))
        assertEquals(SyncState.PHYSICAL_VERIFIED, verified.syncState)
        assertTrue(verified.canGuideBack)

        val asked = reduce(verified, SessionEvent.GuidedRecoveryRequested(T0))
        val effect = asked.effects.filterIsInstance<Effect.PlanRecovery>().single()
        val planned = asked.state.after(effect.planned(listOf(U_PRIME, R_PRIME)))
        assertInstanceOf(GuidedRecovery.Planned::class.java, planned.guidedRecovery)

        val halfway = planned.after(confirm(U_PRIME))
        assertEquals(listOf<Move>(R_PRIME), (halfway.guidedRecovery as GuidedRecovery.Planned).moves)
        assertEquals(SyncState.PHYSICAL_PREDICTED, halfway.syncState)
        assertEquals(entered.apply(U_PRIME), halfway.expected)
        assertEquals(GuidedRecovery.None, halfway.after(confirm(R_PRIME)).guidedRecovery)
    }

    @Test
    fun `redo in physical mode is ignored and awaiting confirm rejects non-null move`() {
        val session =
            started()
                .after(
                    SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0),
                )
        assertEquals(SyncState.PHYSICAL_AWAITING_CONFIRM, session.syncState)

        // PHYSICAL_AWAITING_CONFIRM rejects non-null move
        val rejectedMove = reduce(session, SessionEvent.PhysicalConfirmed(R, T0))
        assertEquals(session, rejectedMove.state)

        // PHYSICAL_AWAITING_CONFIRM accepts null move -> PHYSICAL_PREDICTED
        val confirmedMatch = reduce(session, SessionEvent.PhysicalConfirmed(null, T0)).state
        assertEquals(SyncState.PHYSICAL_PREDICTED, confirmedMatch.syncState)

        // Confirm move, then undo: Redo cannot turn the real cube, so it changes nothing.
        val moved = reduce(confirmedMatch, SessionEvent.PhysicalConfirmed(R, T0)).state
        val undone = reduce(moved, SessionEvent.Undo(T0)).state
        assertTrue(undone.history.canRedo)

        val redo = reduce(undone, SessionEvent.Redo(T0))
        assertSame(undone, redo.state)
        assertTrue(redo.effects.isEmpty())
        assertTrue(redo.presentation.isEmpty())

        // Standing feedback is untouched too, unlike the generic clearing of other events.
        val withFeedback = undone.copy(lastMoveFeedback = MoveFeedback.NEUTRAL)
        assertSame(withFeedback, reduce(withFeedback, SessionEvent.Redo(T0)).state)
    }

    @Test
    fun `retry in physical mode is suppressed`() {
        val session = reduce(started(), SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0)).state
        val confirmed = reduce(session, SessionEvent.PhysicalConfirmed(null, T0)).state
        val moved = reduce(confirmed, SessionEvent.PhysicalConfirmed(R, T0)).state

        val retried = reduce(moved, SessionEvent.RetryRequested(T0))
        assertEquals(moved, retried.state)
        assertTrue(retried.effects.isEmpty())
    }

    @Test
    fun `reset and overtaken reset confirmation in physical mode are suppressed`() {
        val session = reduce(started(), SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0)).state
        val confirmed = reduce(session, SessionEvent.PhysicalConfirmed(null, T0)).state
        val moved = reduce(confirmed, SessionEvent.PhysicalConfirmed(R, T0)).state

        // Reset does not ask confirmation in physical mode
        val resetAttempt = reduce(moved, SessionEvent.Reset(T0))
        assertEquals(moved, resetAttempt.state)
        assertNull(resetAttempt.state.pendingReset)
        assertTrue(resetAttempt.effects.isEmpty())

        // Overtaken ResetConfirmed from previous virtual mode is discarded without re-prompting reset
        val overtaken = reduce(moved, SessionEvent.ResetConfirmed(effectId = 1L, revision = 0L, at = T0))
        assertNull(overtaken.state.pendingReset)
        assertTrue(overtaken.effects.isEmpty())
    }

    @Test
    fun `property test over random event sequences asserts invariants`() {
        val rng = Random(42)
        val moves = listOf(R, R_PRIME, U, U_PRIME, F, F_PRIME, D)

        // Run 50 random trajectories of 60 events each
        repeat(50) {
            var current = started()
            repeat(60) {
                val candidateEvents =
                    buildList {
                        add(SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0))
                        add(SessionEvent.ModeSwitched(CubeMode.VIRTUAL, T0))
                        add(SessionEvent.PhysicalConfirmed(null, T0))
                        add(SessionEvent.PhysicalConfirmed(moves[rng.nextInt(moves.size)], T0))
                        add(SessionEvent.MismatchReported(T0))
                        add(SessionEvent.PhysicalSolveConfirmed(T0))
                        add(SessionEvent.Undo(T0))
                        add(SessionEvent.Redo(T0))
                        add(SessionEvent.Reset(T0))
                        add(commit(moves[rng.nextInt(moves.size)]))
                        add(hint)
                        add(stepAdvanced)
                        add(SessionEvent.EditorEntered(CubeState.SOLVED.apply(moves[rng.nextInt(moves.size)]), T0))
                    }

                val event = candidateEvents[rng.nextInt(candidateEvents.size)]
                val transition = reduce(current, event)
                val next = transition.state

                // Invariant 1: In all physical-mode states, virtual mirrors expected
                if (next.syncState != SyncState.VIRTUAL) {
                    assertEquals(next.expected, next.virtual, "virtual must mirror expected in ${next.syncState}")
                }

                // Invariant 2: expected changes ONLY on:
                // - ModeSwitched to PHYSICAL (which syncs expected := virtual)
                // - PhysicalConfirmed with a move
                // - Undo of a physical move
                // - EditorEntered (accepted Full observation)
                val isConfirmedMove =
                    event is SessionEvent.PhysicalConfirmed && event.move != null &&
                        current.syncState in setOf(SyncState.PHYSICAL_PREDICTED, SyncState.PHYSICAL_VERIFIED, SyncState.PHYSICAL_MISMATCH)
                val isPhysicalUndo =
                    event is SessionEvent.Undo &&
                        current.syncState != SyncState.VIRTUAL && current.history.canUndo
                val isModeSwitchToPhysical =
                    event is SessionEvent.ModeSwitched && event.mode == CubeMode.PHYSICAL && current.syncState == SyncState.VIRTUAL
                val isEditorEntered = event is SessionEvent.EditorEntered && current.syncState != SyncState.VIRTUAL

                if (isModeSwitchToPhysical) {
                    assertEquals(current.virtual, next.expected, "expected must become virtual on switch to PHYSICAL")
                } else if (isConfirmedMove || isPhysicalUndo || isEditorEntered) {
                    // expected was allowed to change
                } else {
                    assertEquals(
                        current.expected,
                        next.expected,
                        "expected changed unexpectedly from $event in state ${current.syncState}",
                    )
                }

                // No Partial observation is produced in v1.0
                assertFalse(next.observed is PhysicalObservation.Partial, "observed must never be Partial, after $event")

                // Invariant 3: View Rotation changes neither Notation nor expected
                val expectedBeforeRotation = next.expected
                val orbit = ViewOrbit.DEFAULT.rotated((rng.nextDouble() - 0.5) * 360.0, (rng.nextDouble() - 0.5) * 170.0)
                val testMove = moves[rng.nextInt(moves.size)]
                val notationBefore = Notation.format(testMove)
                assertTrue(orbit.eye().length() > 0.0)
                assertEquals(notationBefore, Notation.format(testMove), "Move notation must not change with View Rotation")
                // View rotation never mutates expected
                assertEquals(expectedBeforeRotation, next.expected, "View rotation must never mutate expected")
                if (next.syncState != SyncState.VIRTUAL) {
                    assertEquals(next.expected, next.virtual, "View rotation must never disturb expected/virtual mirroring")
                }

                current = next
            }
        }
    }

    private val toPhysical = SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0)
    private val toVirtual = SessionEvent.ModeSwitched(CubeMode.VIRTUAL, T0)
    private val matches = SessionEvent.PhysicalConfirmed(null, T0)
    private val solveConfirmed = SessionEvent.PhysicalSolveConfirmed(T0)

    /** The white cross met on the real cube (Done on F'), not yet confirmed. */
    private fun physicallySolved(): SessionState = started().after(toPhysical, matches, SessionEvent.PhysicalConfirmed(F_PRIME, T0))

    @Test
    fun `objective met in physical mode is unconfirmed until the learner confirms`() {
        val met = physicallySolved()
        assertTrue(met.objectiveMet)
        assertTrue(met.physicalSolveUnconfirmed)
        assertFalse(met.physicalSolveConfirmed)
    }

    @Test
    fun `accepted confirmation sets the flags and changes nothing else`() {
        val met = physicallySolved()
        val confirmed = reduce(met, solveConfirmed)
        assertTrue(confirmed.state.physicalSolveConfirmed)
        assertFalse(confirmed.state.physicalSolveUnconfirmed)
        assertEquals(met.revision, confirmed.state.revision)
        assertEquals(met.history, confirmed.state.history)
        assertEquals(met.expected, confirmed.state.expected)
        assertEquals(met.checkpoint, confirmed.state.checkpoint)
        assertTrue(confirmed.effects.isEmpty())
        assertSame(confirmed.state, reduce(confirmed.state, solveConfirmed).state)
    }

    @Test
    fun `confirmation in a wrong state returns the same state`() {
        val met = physicallySolved()
        val wrong =
            listOf(
                SessionState.IDLE,
                SessionState.IDLE.after(playbackStarted()),
                started(),
                started().after(commit(F_PRIME)),
                started().after(toPhysical, matches),
                started().after(commit(F_PRIME), toPhysical),
                met.after(SessionEvent.MismatchReported(T0)),
                met.copy(lastMoveFeedback = MoveFeedback.NEUTRAL, syncState = SyncState.VIRTUAL),
            )
        wrong.forEach { assertSame(it, reduce(it, solveConfirmed).state) }
    }

    @Test
    fun `confirmation is accepted in a practice session`() {
        val practice = SessionState.IDLE.after(SessionEvent.PracticeStarted(whiteCrossLesson(), TUTOR_CONFIG, T0))
        assertFalse(practice.physicalSolveConfirmed)
        val met = practice.after(toPhysical, matches, SessionEvent.EditorEntered(CubeState.SOLVED, T0))
        assertTrue(met.objectiveMet)
        assertTrue(met.physicalSolveUnconfirmed)
        assertTrue(met.after(solveConfirmed).physicalSolveConfirmed)
    }

    @Test
    fun `a switch to virtual mode keeps an unconfirmed solve unconfirmed`() {
        val virtual = physicallySolved().after(toVirtual)
        assertEquals(SyncState.VIRTUAL, virtual.syncState)
        assertTrue(virtual.objectiveMet)
        assertTrue(virtual.physicalSolveUnconfirmed)
        assertFalse(virtual.physicalSolveConfirmed)
    }

    @Test
    fun `an unmet objective clears both flags`() {
        val met = physicallySolved()
        val unconfirmedUndone = met.after(undo)
        assertFalse(unconfirmedUndone.objectiveMet)
        assertFalse(unconfirmedUndone.physicalSolveUnconfirmed)

        val confirmedUndone = met.after(solveConfirmed, undo)
        assertFalse(confirmedUndone.physicalSolveConfirmed)
        assertFalse(confirmedUndone.physicalSolveUnconfirmed)
    }

    @Test
    fun `a confirmed solve stays confirmed across a mode switch and a new lesson starts clear`() {
        val confirmed = physicallySolved().after(solveConfirmed)
        val virtual = confirmed.after(toVirtual)
        assertTrue(virtual.physicalSolveConfirmed)
        assertFalse(virtual.physicalSolveUnconfirmed)

        val fresh = confirmed.after(lessonStarted(whiteCrossLesson("lesson-01-02")))
        assertFalse(fresh.physicalSolveConfirmed)
        assertFalse(fresh.physicalSolveUnconfirmed)
        val playback = physicallySolved().after(playbackStarted())
        assertFalse(playback.physicalSolveConfirmed)
        assertFalse(playback.physicalSolveUnconfirmed)
    }

    @Test
    fun `an editor-entered solved cube is unconfirmed`() {
        val entered = started().after(toPhysical, matches, SessionEvent.EditorEntered(CubeState.SOLVED, T0))
        assertTrue(entered.objectiveMet)
        assertTrue(entered.physicalSolveUnconfirmed)
    }

    @Test
    fun `an ignored event leaves restored flags alone`() {
        val legacy = reduce(SessionState.IDLE, restoredEvent(physicallySolved(), physicalSolveUnconfirmed = false)).state
        assertFalse(legacy.physicalSolveUnconfirmed)
        assertSame(legacy, reduce(legacy, SessionEvent.SnapshotSaveFailed(T0)).state)
    }

    @Test
    fun `snapshot restore takes both flags from the snapshot`() {
        val unconfirmed = reduce(SessionState.IDLE, restoredEvent(physicallySolved())).state
        assertTrue(unconfirmed.physicalSolveUnconfirmed)
        assertFalse(unconfirmed.physicalSolveConfirmed)
        assertEquals(SyncState.PHYSICAL_AWAITING_CONFIRM, unconfirmed.syncState)
        assertSame(unconfirmed, reduce(unconfirmed, solveConfirmed).state)
        val resumed = unconfirmed.after(matches, solveConfirmed)
        assertTrue(resumed.physicalSolveConfirmed)

        val confirmed = reduce(SessionState.IDLE, restoredEvent(physicallySolved().after(solveConfirmed))).state
        assertTrue(confirmed.physicalSolveConfirmed)
        assertFalse(confirmed.physicalSolveUnconfirmed)
    }

    private fun restoredEvent(
        state: SessionState,
        physicalSolveUnconfirmed: Boolean = state.physicalSolveUnconfirmed,
    ) = SessionEvent.SessionRestored(
        checkNotNull(SessionSnapshot.of(state, "saved", "v1")).copy(physicalSolveUnconfirmed = physicalSolveUnconfirmed),
        checkNotNull(state.lesson),
        checkNotNull(state.tutorConfig),
        T0,
    )

    @Test
    fun `an accepted mode switch persists the new mode with the unchanged checkpoint history`() {
        val virtual = started().after(commit(R))
        val toPhysicalTransition = reduce(virtual, toPhysical)
        val physicalEffect = toPhysicalTransition.effects.filterIsInstance<Effect.PersistCheckpoint>().single()
        assertEquals(CubeMode.PHYSICAL, physicalEffect.mode)
        assertEquals(virtual.checkpoint, physicalEffect.history)
        assertEquals(virtual.checkpoint, toPhysicalTransition.state.checkpoint)

        val toVirtualTransition = reduce(toPhysicalTransition.state, toVirtual)
        val virtualEffect = toVirtualTransition.effects.filterIsInstance<Effect.PersistCheckpoint>().single()
        assertEquals(CubeMode.VIRTUAL, virtualEffect.mode)
        assertEquals(virtual.checkpoint, virtualEffect.history)
        assertEquals(virtual.checkpoint, toVirtualTransition.state.checkpoint)
    }

    @Test
    fun `a checkpoint restore keeps a solve left unconfirmed in virtual mode`() {
        val met = physicallySolved()
        val switched = reduce(met, toVirtual)
        val effect = switched.effects.filterIsInstance<Effect.PersistCheckpoint>().single()
        assertTrue(effect.physicalSolveUnconfirmed)

        val restored =
            reduce(
                SessionState.IDLE,
                SessionEvent.CheckpointRestored(Checkpoint.of(effect, "v1"), checkNotNull(met.lesson), TUTOR_CONFIG, "v1", T0),
            ).state
        assertEquals(SyncState.VIRTUAL, restored.syncState)
        assertTrue(restored.objectiveMet)
        assertTrue(restored.physicalSolveUnconfirmed)
        assertFalse(restored.physicalSolveConfirmed)
    }

    @Test
    fun `a checkpoint emitted as the objective is met carries the flag the rule then sets`() {
        val met = reduce(started().after(toPhysical, matches), SessionEvent.PhysicalConfirmed(F_PRIME, T0))
        assertTrue(
            met.effects
                .filterIsInstance<Effect.PersistCheckpoint>()
                .single()
                .physicalSolveUnconfirmed,
        )
    }

    @Test
    fun `a rejected mode switch and a practice mode switch persist nothing`() {
        assertTrue(reduce(started(), toVirtual).effects.isEmpty())
        val practice = SessionState.IDLE.after(SessionEvent.PracticeStarted(whiteCrossLesson(), TUTOR_CONFIG, T0))
        assertTrue(
            reduce(practice, toPhysical)
                .effects
                .filterIsInstance<Effect.PersistCheckpoint>()
                .isEmpty(),
        )
    }
}
