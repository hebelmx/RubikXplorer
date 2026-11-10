package dev.cubentia.application.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionEndedTest {
    @Test fun `ending every active kind clears all state and closes only an open attempt`() {
        val lesson = whiteCrossLesson()
        val event = lessonStarted(lesson)
        val started = reduce(SessionState.IDLE, event).state
        for (kind in SessionKind.entries) {
            for (open in listOf(false, true)) {
                val before = started.copy(kind = kind, attemptOpen = open, hintsUsed = 3)
                val ended = reduce(before, SessionEvent.SessionEnded(event.at))
                assertEquals(
                    SessionState.IDLE.copy(
                        revision = before.revision + 1,
                        nextEffectId = before.nextEffectId + if (open) 1 else 0,
                    ),
                    ended.state,
                )
                assertTrue(ended.presentation.isEmpty())
                if (open) {
                    val effect = ended.effects.single() as Effect.AttemptEnded
                    assertEquals(AttemptOutcome.ABANDONED, effect.outcome)
                    assertEquals(3, effect.hints)
                    assertEquals(before.nextEffectId, effect.effectId)
                    assertEquals(ended.state.revision, effect.revision)
                    assertEquals(event.at, effect.at)
                } else {
                    assertTrue(ended.effects.isEmpty())
                }
                assertEquals(Transition(ended.state), reduce(ended.state, SessionEvent.SessionEnded(event.at)))
            }
        }
    }
}
