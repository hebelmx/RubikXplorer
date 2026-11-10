package dev.cubentia.application.session

import dev.cubentia.domain.content.LessonId
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.tutor.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SessionSnapshotTest {
    private val lesson = twoStepLesson()

    private fun snapshot(state: SessionState) = checkNotNull(SessionSnapshot.of(state, "old-session", "content-v1"))

    private fun restore(
        snapshot: SessionSnapshot,
        state: SessionState = SessionState.IDLE,
    ) = reduce(state, SessionEvent.SessionRestored(snapshot, lesson, TUTOR_CONFIG, T0))

    @Test fun `idle is not captured and only taught sessions currently resume`() {
        assertNull(SessionSnapshot.of(SessionState.IDLE, "id", "v1"))
        val saved = snapshot(started(lesson))
        for (kind in SessionKind.entries.filter { !it.isTaught }) {
            assertSame(SessionState.IDLE, restore(saved.copy(kind = kind)).state)
        }
        assertFalse(SessionKind.SOLVE_PLAYBACK.resumesOnColdStart)
        assertTrue(SessionKind.PRACTICE.resumesOnColdStart)
        assertTrue(SessionKind.TWO_LOOK.resumesOnColdStart)
    }

    @Test fun `restore keeps undo redo checkpoint and tutor while clearing all transients`() {
        val before =
            started(lesson).after(
                commit(U),
                stepAdvanced,
                commit(R),
                hint,
                modeChanged(TeachingMode.TEST_ME),
                undo,
                resetEvent,
            )
        val transition = restore(snapshot(before))
        val after = transition.state
        assertEquals(before.history, after.history)
        assertEquals(before.checkpoint, after.checkpoint)
        assertEquals(snapshot(before).copy(revision = 1), snapshot(after))
        assertEquals(before.progress, after.progress)
        assertEquals(before.objectiveMet, after.objectiveMet)
        assertEquals(before.stepIndex, after.stepIndex)
        assertNull(after.pendingReset)
        assertNull(after.lastDiscarded)
        assertNull(after.lastMoveFeedback)
        assertNull(after.lastDiagnosis)
        assertEquals(GuidedRecovery.None, after.guidedRecovery)
        assertEquals(1L, after.nextEffectId)
        assertEquals(listOf(Presentation.Snap(1, after.virtual)), transition.presentation)
        assertTrue(transition.effects.isEmpty())
        assertTrue(after.history.canRedo)
        assertEquals(before.history.redo(), after.after(redo).history)
    }

    @Test fun `physical solve flags are captured and restored`() {
        val physical = started().after(SessionEvent.ModeSwitched(CubeMode.PHYSICAL, T0), SessionEvent.PhysicalConfirmed(null, T0))
        val met = physical.after(SessionEvent.PhysicalConfirmed(F_PRIME, T0))
        val saved = checkNotNull(SessionSnapshot.of(met, "s", "v1"))
        assertTrue(saved.physicalSolveUnconfirmed)
        assertFalse(saved.physicalSolveConfirmed)

        val confirmed = met.after(SessionEvent.PhysicalSolveConfirmed(T0))
        val savedConfirmed = checkNotNull(SessionSnapshot.of(confirmed, "s", "v1"))
        assertTrue(savedConfirmed.physicalSolveConfirmed)
        assertFalse(savedConfirmed.physicalSolveUnconfirmed)
    }

    @Test fun `a Checkpoint left on another branch by Undo and a different Move still restores`() {
        // U completes step 1 (Checkpoint [U]); Undo below the boundary and R keep the Checkpoint at [U].
        val before = started(lesson).after(commit(U), undo, commit(R))
        assertEquals(listOf(U), before.checkpoint?.done)
        assertEquals(listOf(R), before.history.done)
        val after = restore(snapshot(before)).state
        assertEquals(before.history, after.history)
        assertEquals(before.checkpoint, after.checkpoint)
    }

    @Test fun `corrupt notation facelets mismatched lesson and active state are ignored`() {
        val saved = snapshot(started(lesson))
        val bad =
            listOf(
                saved.copy(start = "bad"),
                saved.copy(start = "W".repeat(54)),
                saved.copy(lessonId = "missing"),
                saved.copy(done = "bad"),
                saved.copy(undone = "bad"),
                saved.copy(solution = "bad"),
                saved.copy(checkpoint = "bad"),
            )
        bad.forEach { assertSame(SessionState.IDLE, restore(it).state) }
        val active = started(lesson).after(commit(R))
        assertSame(active, restore(saved, active).state)
        assertSame(
            SessionState.IDLE,
            reduce(
                SessionState.IDLE,
                SessionEvent.SessionRestored(saved, lesson.copy(id = LessonId("different")), TUTOR_CONFIG, T0),
            ).state,
        )
    }

    @Test fun `snapshot entry may be a stored cube from a restarted Lesson`() {
        val saved = snapshot(started(lesson)).copy(start = whiteCrossLesson().entryState)
        val restored = restore(saved).state
        assertTrue(restored.isActive)
        assertEquals(saved.start, snapshot(restored).start)
    }

    @Test fun `seeded lesson events round trip at many points`() {
        val random = Random(61001)
        var state = SessionState.IDLE.after(lessonStarted(lesson, adaptiveAssistance = true))
        val events =
            listOf(
                commit(U),
                commit(R),
                commit(F),
                undo,
                redo,
                hint,
                reveal,
                stepAdvanced,
                modeChanged(TeachingMode.TEST_ME),
                modeChanged(TeachingMode.GUIDE_ME),
                retry,
                usualHelp,
                resetEvent,
            )
        repeat(400) {
            state = state.after(events[random.nextInt(events.size)])
            state.pendingReset?.let { state = state.after(if (random.nextBoolean()) it.confirm() else it.decline()) }
            val saved = snapshot(state)
            val restored = restore(saved).state
            assertEquals(saved.copy(revision = 1), snapshot(restored), "event $it; ${Notation.format(state.history.done)}")
            assertEquals(state.history, restored.history)
            assertEquals(state.checkpoint, restored.checkpoint)
            assertEquals(state.objectiveMet, restored.objectiveMet)
            assertEquals(state.progress, restored.progress)
        }
    }
}
