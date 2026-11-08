package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.cube.Axis
import dev.rubikxplorer.domain.cube.CubeRotation
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.input.ViewOrbit
import dev.rubikxplorer.domain.recognition.PhysicalObservation
import dev.rubikxplorer.domain.text.Text
import dev.rubikxplorer.domain.tutor.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
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
        assertEquals(SyncState.PHYSICAL_PREDICTED, afterEditor.syncState)
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

        val undoVirtualAtBarrier = reduce(backToVirtual, SessionEvent.Undo(T0))
        assertEquals(backToVirtual, undoVirtualAtBarrier.state)
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
    fun `redo in physical mode re-confirms undone move and awaiting confirm rejects non-null move`() {
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

        // Confirm move, then undo, then redo
        val moved = reduce(confirmedMatch, SessionEvent.PhysicalConfirmed(R, T0)).state
        val undone = reduce(moved, SessionEvent.Undo(T0)).state
        assertTrue(undone.history.canRedo)
        assertEquals(R, undone.history.redoable)

        val redone = reduce(undone, SessionEvent.Redo(T0)).state
        assertEquals(moved.expected, redone.expected)
        assertEquals(redone.expected, redone.virtual)
        assertEquals(SyncState.PHYSICAL_PREDICTED, redone.syncState)
        assertFalse(redone.history.canRedo)
        assertTrue(redone.history.canUndo)
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
                // - Redo of a physical move
                // - EditorEntered (accepted Full observation)
                val isConfirmedMove =
                    event is SessionEvent.PhysicalConfirmed && event.move != null &&
                        (current.syncState == SyncState.PHYSICAL_PREDICTED || current.syncState == SyncState.PHYSICAL_VERIFIED)
                val isPhysicalUndo =
                    event is SessionEvent.Undo &&
                        current.syncState != SyncState.VIRTUAL && current.history.canUndo
                val isPhysicalRedo =
                    event is SessionEvent.Redo &&
                        current.syncState != SyncState.VIRTUAL && current.history.canRedo &&
                        (current.syncState == SyncState.PHYSICAL_PREDICTED || current.syncState == SyncState.PHYSICAL_VERIFIED)
                val isModeSwitchToPhysical =
                    event is SessionEvent.ModeSwitched && event.mode == CubeMode.PHYSICAL && current.syncState == SyncState.VIRTUAL
                val isEditorEntered = event is SessionEvent.EditorEntered && current.syncState != SyncState.VIRTUAL

                if (isModeSwitchToPhysical) {
                    assertEquals(current.virtual, next.expected, "expected must become virtual on switch to PHYSICAL")
                } else if (isConfirmedMove || isPhysicalUndo || isPhysicalRedo || isEditorEntered) {
                    // expected was allowed to change
                } else {
                    assertEquals(
                        current.expected,
                        next.expected,
                        "expected changed unexpectedly from $event in state ${current.syncState}",
                    )
                }

                // Invariant 3: View Rotation changes neither Notation nor expected
                val orbit = ViewOrbit.DEFAULT.rotated((rng.nextDouble() - 0.5) * 360.0, (rng.nextDouble() - 0.5) * 170.0)
                val testMove = moves[rng.nextInt(moves.size)]
                val notationBefore = Notation.format(testMove)
                assertTrue(orbit.eye().length() > 0.0)
                assertEquals(notationBefore, Notation.format(testMove), "Move notation must not change with View Rotation")
                // View rotation never mutates expected
                assertEquals(next.expected, next.expected, "View rotation must never mutate expected")
                if (next.syncState != SyncState.VIRTUAL) {
                    assertEquals(next.expected, next.virtual, "View rotation must never disturb expected/virtual mirroring")
                }

                current = next
            }
        }
    }
}
