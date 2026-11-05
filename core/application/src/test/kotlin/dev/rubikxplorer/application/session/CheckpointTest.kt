package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.text.Text
import dev.rubikxplorer.domain.tutor.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CheckpointTest {
    private val lesson = twoStepLesson()
    private val started = reduce(SessionState.IDLE, lessonStarted(lesson, adaptiveAssistance = true))
    private val initial = started.state
    private val boundary = reduce(initial, commit(U))
    private val effect = boundary.effects.single() as Effect.PersistCheckpoint
    private val dto = Checkpoint.of(effect, "v1")

    private fun restore(
        dto: Checkpoint = this.dto,
        revision: String = "v1",
        state: SessionState = SessionState.IDLE,
    ) = reduce(state, SessionEvent.CheckpointRestored(dto, lesson, TUTOR_CONFIG, revision, T0))

    @Test fun `start and boundaries persist exact committed data with sequential ids`() {
        val first = started.effects.single() as Effect.PersistCheckpoint
        assertEquals(1L, first.effectId)
        assertEquals(2L, initial.nextEffectId)
        assertEquals(initial.nextEffectId, effect.effectId)
        assertEquals(boundary.state.revision, effect.revision)
        assertEquals(effect.effectId + 1, boundary.state.nextEffectId)
        assertEquals(SessionKind.LESSON, dto.kind)
        assertEquals(lesson.id.value, dto.lessonId)
        assertEquals(lesson.stageId.value, dto.stageId)
        assertEquals("v1", dto.contentRevision)
        assertEquals(FaceletString.encode(initial.virtual.toFacelets()), dto.start)
        assertEquals(FaceletString.encode(boundary.state.virtual.toFacelets()), dto.cube)
        assertEquals("UF", dto.startOrientation)
        assertEquals("UF", dto.cubeOrientation)
        assertEquals("U", dto.history)
        assertEquals(CubeMode.VIRTUAL, dto.mode)
        assertEquals(initial.teachingMode, dto.teachingMode)
        assertTrue(dto.adaptiveAssistance)
        assertEquals(T0.toEpochMilliseconds(), dto.savedAt)
        val asked = reduce(boundary.state, resetEvent)
        assertTrue(asked.effects.single() is Effect.ConfirmReset)
        val reset = reduce(asked.state, asked.state.pendingReset!!.confirm())
        assertEquals(initial.history, (reset.effects.single() as Effect.PersistCheckpoint).history)
    }

    @Test fun `ordinary moves tutor events idle playback and snapshot restore never persist`() {
        assertTrue(reduce(initial, commit(R)).effects.isEmpty())
        for (event in listOf(hint, reveal, modeChanged(TeachingMode.TEST_ME), stepAdvanced, retry)) {
            assertTrue(reduce(initial, event).effects.isEmpty())
            assertTrue(reduce(SessionState.IDLE, event).effects.isEmpty())
        }
        assertTrue(reduce(SessionState.IDLE, playbackStarted()).effects.isEmpty())
        val snapshot = checkNotNull(SessionSnapshot.of(boundary.state, "id", "v1"))
        assertTrue(reduce(SessionState.IDLE, SessionEvent.SessionRestored(snapshot, lesson, TUTOR_CONFIG, T0)).effects.isEmpty())
    }

    @Test fun `same content resumes undoable history without redo and fresh tutor`() {
        val restored = restore(dto.copy(teachingMode = TeachingMode.TEST_ME)).state
        assertEquals(boundary.state.history, restored.history)
        assertEquals(restored.history, restored.checkpoint)
        assertFalse(restored.history.canRedo)
        assertTrue(restored.history.canUndo)
        assertNull(restored.restoreNote)
        assertEquals(TeachingMode.TEST_ME, restored.teachingMode)
        assertTrue(restored.adaptiveAssistance)
        assertEquals(0, restored.hintsUsed)
        assertTrue(restored.stepMistakes.isEmpty())
        assertEquals(1L, restored.revision)
        assertEquals(listOf(Presentation.Snap(1, restored.virtual)), restore().presentation)
        assertTrue(restore().effects.single() is Effect.PersistCheckpoint)
    }

    @Test fun `rotated cubes with wide and slice turns round trip through both restore paths`() {
        val moves =
            (
                dev.rubikxplorer.domain.cube.Notation.parse(
                    "y R x M r' z2 U",
                ) as dev.rubikxplorer.domain.cube.NotationResult.Parsed
            ).moves
        val history =
            moves.fold(
                dev.rubikxplorer.domain.cube
                    .MoveHistory(initial.entry),
            ) { h, move -> h.commit(move) }
        val rotated =
            Checkpoint.of(
                Effect.PersistCheckpoint(1, 1, lesson.id.value, lesson.stageId.value, history, TeachingMode.GUIDE_ME, false, T0),
                "v1",
            )
        assertFalse(rotated.cubeOrientation == "UF")
        val resumed = restore(rotated).state
        assertEquals(history, resumed.history)
        assertNull(resumed.restoreNote)
        val restarted = restore(rotated, revision = "v2").state
        assertEquals(history.state, restarted.entry)
        assertEquals(history.state, restarted.virtual)
        assertEquals(Text("msg.lesson.updated"), restarted.restoreNote)
        val again =
            restore(
                Checkpoint.of(
                    restarted.history.let { h ->
                        Effect.PersistCheckpoint(2, 2, lesson.id.value, lesson.stageId.value, h, TeachingMode.GUIDE_ME, false, T0)
                    },
                    "v2",
                ),
                revision = "v2",
            ).state
        assertEquals(history.state, again.virtual)
        assertNull(again.restoreNote)
    }

    @Test fun `changed content or stage restarts at cube and later snapshot preserves its entry`() {
        for (changed in listOf(dto.copy(contentRevision = "old"), dto.copy(stageId = "old"))) {
            val restored = restore(changed).state
            assertEquals(boundary.state.virtual, restored.entry)
            assertEquals(restored.entry, restored.virtual)
            assertTrue(restored.history.done.isEmpty())
            assertFalse(restored.history.canUndo || restored.history.canRedo)
            assertEquals(Text("msg.lesson.updated"), restored.restoreNote)
            val snapshot = checkNotNull(SessionSnapshot.of(restored, "id", "v1"))
            val again = reduce(SessionState.IDLE, SessionEvent.SessionRestored(snapshot, lesson, TUTOR_CONFIG, T0)).state
            assertEquals(restored.history, again.history)
            assertNull(again.restoreNote)
            for (event in listOf(
                hint,
                reveal,
                commit(R),
                undo,
                redo,
                resetEvent,
                stepAdvanced,
                retry,
                guideBack,
                modeChanged(TeachingMode.TEST_ME),
                usualHelp,
                SessionEvent.RecoveryPlanFailed(99, 99, T0),
            )) {
                assertNull(reduce(restored, event).state.restoreNote)
            }
            for (event in listOf(
                SessionEvent.CheckpointSaveFailed(T0),
                SessionEvent.CheckpointLoadFailed(T0),
                SessionEvent.SnapshotSaveFailed(T0),
                SessionEvent.SnapshotLoadFailed(T0),
            )) {
                assertSame(restored, reduce(restored, event).state)
            }
        }
    }

    @Test fun `invalid checkpoints and non idle restores are ignored without effects`() {
        val invalid =
            listOf(
                dto.copy(kind = SessionKind.SOLVE_PLAYBACK),
                dto.copy(lessonId = "gone"),
                dto.copy(start = "bad"),
                dto.copy(cube = "W".repeat(54)),
                dto.copy(cube = "bad"),
                dto.copy(startOrientation = "UR"),
                dto.copy(cubeOrientation = "FU"),
                dto.copy(history = "bad"),
                dto.copy(cube = FaceletString.encode(CubeState.SOLVED.toFacelets())),
            )
        for (bad in invalid) {
            val restored = restore(bad)
            assertSame(SessionState.IDLE, restored.state)
            assertTrue(restored.effects.isEmpty())
        }
        assertSame(initial, restore(state = initial).state)
        val playback = reduce(SessionState.IDLE, playbackStarted()).state
        assertSame(playback, restore(state = playback).state)
    }
}
