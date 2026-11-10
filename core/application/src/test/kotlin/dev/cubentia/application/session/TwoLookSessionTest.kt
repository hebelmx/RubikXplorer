package dev.cubentia.application.session

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.tutor.AttemptOutcome
import dev.cubentia.domain.tutor.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TwoLookSessionTest {
    @Test fun `two look takes taught moves and restores without writing checkpoints`() {
        val lesson = twoStepLesson()
        val start = reduce(SessionState.IDLE, SessionEvent.LessonStarted(lesson, TUTOR_CONFIG, T0, kind = SessionKind.TWO_LOOK))
        assertEquals(SessionKind.TWO_LOOK, start.state.kind)
        assertTrue(start.state.isActive)
        val moved = reduce(start.state, commit(U))
        assertNotEquals(start.state.virtual, moved.state.virtual)
        assertFalse((start.effects + moved.effects).any { it is Effect.PersistCheckpoint })
        val snapshot = checkNotNull(SessionSnapshot.of(moved.state, "two-look", "revision"))
        assertEquals("revision", snapshot.contentRevision)
        val restored = reduce(SessionState.IDLE, SessionEvent.SessionRestored(snapshot, lesson, TUTOR_CONFIG, T0)).state
        assertEquals(SessionKind.TWO_LOOK, restored.kind)
        assertEquals(moved.state.virtual, restored.virtual)
        assertEquals(lesson, restored.lesson)
    }

    private val entry =
        (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded)
            .bundle.twoLookStages
            .first()
            .lessons
            .first()

    private fun taught(mode: TeachingMode? = null) =
        reduce(SessionState.IDLE, SessionEvent.LessonStarted(entry, TUTOR_CONFIG, T0, mode = mode, kind = SessionKind.TWO_LOOK))

    @Test fun `two look climbs the Hint Ladder and changes Teaching Mode like a Lesson`() {
        val hinted = taught().state.after(SessionEvent.HintRequested(T0))
        assertEquals(1, hinted.hintsUsed)
        val tested = taught().state.after(modeChanged(TeachingMode.TEST_ME))
        assertEquals(TeachingMode.TEST_ME, tested.teachingMode)
        assertEquals(SessionKind.TWO_LOOK, tested.kind)
    }

    @Test fun `two look opens an attempt and completes it at the Objective`() {
        val started = taught()
        assertTrue(started.effects.any { it is Effect.AttemptStarted })
        var state = started.state
        val endings = mutableListOf<Effect>()
        (Notation.parse(entry.demonstration) as NotationResult.Parsed).moves.forEach { move ->
            val next = reduce(state, commit(move))
            endings += next.effects.filterIsInstance<Effect.AttemptEnded>()
            state = next.state
        }
        assertTrue(state.objectiveMet)
        assertEquals(AttemptOutcome.COMPLETED, (endings.single() as Effect.AttemptEnded).outcome)
    }

    @Test fun `two look offers Guided recovery when the first two layers are disturbed`() {
        val harmed = taught(TeachingMode.TEST_ME).state.after(commit((Notation.parse("D") as NotationResult.Parsed).moves.single()))
        assertTrue(harmed.canGuideBack)
    }
}
