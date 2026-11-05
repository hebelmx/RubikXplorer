package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.tutor.AssistanceLevel
import dev.rubikxplorer.domain.tutor.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttemptTest {
    @Test fun `start complete and re-complete emit once with ordered ids`() {
        val start = reduce(SessionState.IDLE, lessonStarted(whiteCrossLesson()))
        assertTrue(start.state.attemptOpen)
        assertEquals(listOf(1L, 2L), start.effects.map { it.effectId })
        assertEquals(listOf(Effect.PersistCheckpoint::class, Effect.AttemptStarted::class), start.effects.map { it::class })
        val done = reduce(start.state, commit(F_PRIME))
        val end = done.effects.filterIsInstance<Effect.AttemptEnded>().single()
        assertEquals(AttemptOutcome.COMPLETED, end.outcome)
        assertEquals(0, end.errors)
        assertEquals(0, end.hints)
        assertEquals(AssistanceLevel.USUAL, end.assistanceLevel)
        assertFalse(done.state.attemptOpen)
        assertEquals(listOf(3L, 4L), done.effects.map { it.effectId })
        assertTrue(reduce(done.state.after(undo), redo).effects.none { it is Effect.AttemptEnded })
        assertEquals(AttemptEvent.ENDED, AttemptRecord.of(end, "session").event)
    }

    @Test fun `abandon carries old counters and peak help before checkpoint and start`() {
        var state = reduce(SessionState.IDLE, lessonStarted(shippedLesson("lesson-03-01"), adaptiveAssistance = true)).state
        state = state.after(modeChanged(TeachingMode.TEST_ME), hint, commit(R), undo, commit(R), undo, commit(R), undo)
        assertTrue(state.stepMistakes.values.sum() > 0)
        assertTrue(state.attemptAssistance > AssistanceLevel.USUAL)
        val replaced = reduce(state, lessonStarted(whiteCrossLesson()))
        assertEquals(
            listOf(
                Effect.AttemptEnded::class,
                Effect.PersistCheckpoint::class,
                Effect.AttemptStarted::class,
            ),
            replaced.effects.map {
                it::class
            },
        )
        assertEquals((state.nextEffectId..state.nextEffectId + 2).toList(), replaced.effects.map { it.effectId })
        val end = replaced.effects.first() as Effect.AttemptEnded
        assertEquals(state.stepMistakes.values.sum(), end.errors)
        assertEquals(state.hintsUsed, end.hints)
        assertEquals(state.attemptAssistance, end.assistanceLevel)
        assertEquals(AttemptOutcome.ABANDONED, end.outcome)
        assertEquals(replaced.state.revision, end.revision)
    }

    @Test fun `completion records harmful moves hints and peak despite dismissed help`() {
        val lesson = shippedLesson("lesson-03-01")
        var state = reduce(SessionState.IDLE, lessonStarted(lesson, adaptiveAssistance = true)).state
        state = state.after(modeChanged(TeachingMode.TEST_ME), hint, commit(R), undo)
        val peak = state.attemptAssistance
        assertTrue(peak > AssistanceLevel.USUAL)
        assertEquals(1, state.hintsUsed)
        assertEquals(1, state.stepMistakes.values.sum())
        state = state.after(usualHelp)
        assertEquals(AssistanceLevel.USUAL, state.assistance)
        val moves =
            (
                dev.rubikxplorer.domain.cube.Notation
                    .parse(lesson.demonstration) as
                    dev.rubikxplorer.domain.cube.NotationResult.Parsed
            ).moves
        val ends = mutableListOf<Effect.AttemptEnded>()
        for (move in moves) {
            val transition = reduce(state, commit(move))
            ends += transition.effects.filterIsInstance<Effect.AttemptEnded>()
            state = transition.state
        }
        assertTrue(state.objectiveMet)
        val end = ends.single()
        assertEquals(1, end.errors)
        assertEquals(1, end.hints)
        assertEquals(peak, end.assistanceLevel)
        assertEquals(AttemptOutcome.COMPLETED, end.outcome)
    }

    @Test fun `undo retry reset and redo can close an open restored attempt`() {
        val lesson = whiteCrossLesson()
        val start = reduce(SessionState.IDLE, lessonStarted(lesson)).state
        val snapshot = checkNotNull(SessionSnapshot.of(start, "s", "v1"))
        val disturbed =
            snapshot.copy(
                start =
                    dev.rubikxplorer.domain.content.FaceletString.encode(
                        dev.rubikxplorer.domain.cube.CubeState.SOLVED
                            .toFacelets(),
                    ),
                done = "R",
                checkpoint = "",
            )

        fun restored(snapshot: SessionSnapshot) =
            reduce(SessionState.IDLE, SessionEvent.SessionRestored(snapshot, lesson, TUTOR_CONFIG, T0)).state
        val state = restored(disturbed)
        val asked = reduce(state, resetEvent).state
        val transitions =
            listOf(
                reduce(state, undo),
                reduce(state, retry),
                reduce(asked, checkNotNull(asked.pendingReset).confirm()),
                reduce(restored(snapshot.copy(undone = "F'")), redo),
            )
        for (transition in transitions) {
            assertTrue(transition.state.objectiveMet)
            assertFalse(transition.state.attemptOpen)
            assertEquals(
                AttemptOutcome.COMPLETED,
                transition.effects
                    .filterIsInstance<Effect.AttemptEnded>()
                    .single()
                    .outcome,
            )
        }
    }

    @Test fun `snapshot continues attempt and checkpoint starts only unmet objective`() {
        val lesson = whiteCrossLesson()
        val start = reduce(SessionState.IDLE, lessonStarted(lesson))
        val snapshot = checkNotNull(SessionSnapshot.of(start.state, "s", "v1")).copy(attemptAssistance = AssistanceLevel.GUIDE)
        val restored = reduce(SessionState.IDLE, SessionEvent.SessionRestored(snapshot, lesson, TUTOR_CONFIG, T0))
        assertTrue(restored.effects.isEmpty())
        assertTrue(restored.state.attemptOpen)
        assertEquals(AssistanceLevel.GUIDE, restored.state.attemptAssistance)
        val ended = reduce(restored.state, commit(F_PRIME)).effects.filterIsInstance<Effect.AttemptEnded>().single()
        assertEquals(AssistanceLevel.GUIDE, ended.assistanceLevel)
        for (transition in listOf(start, reduce(start.state, commit(F_PRIME)))) {
            val cp = Checkpoint.of(transition.effects.filterIsInstance<Effect.PersistCheckpoint>().single(), "v1")
            val resumed = reduce(SessionState.IDLE, SessionEvent.CheckpointRestored(cp, lesson, TUTOR_CONFIG, "v1", T0))
            assertEquals(!resumed.state.objectiveMet, resumed.state.attemptOpen)
            assertEquals(if (resumed.state.objectiveMet) 0 else 1, resumed.effects.filterIsInstance<Effect.AttemptStarted>().size)
        }
        assertTrue(reduce(SessionState.IDLE, hint).effects.isEmpty())
        val playback = reduce(SessionState.IDLE, playbackStarted())
        assertTrue(playback.effects.isEmpty())
        assertFalse(playback.state.attemptOpen)
    }
}
