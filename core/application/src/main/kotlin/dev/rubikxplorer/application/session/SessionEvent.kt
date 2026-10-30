package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.tutor.TeachingMode
import kotlin.time.Instant

/**
 * Every change to a `LearningSession` (AD-3), applied by [reduce]. Each event carries the [at]
 * the session host stamped at dispatch; `session/TRANSITIONS.md` lists what each one mutates.
 */
sealed interface SessionEvent {
    val at: Instant

    /** Starts a Lesson session at the Lesson's entry state, replacing any current session. */
    data class LessonStarted(
        val lesson: Lesson,
        override val at: Instant,
    ) : SessionEvent

    /** The Learner committed [move] (commit before animate, AD-4). */
    data class MoveCommitted(
        val move: Move,
        override val at: Instant,
    ) : SessionEvent

    /** Takes back the last committed Move. */
    data class Undo(
        override val at: Instant,
    ) : SessionEvent

    /** Applies the last undone Move again. */
    data class Redo(
        override val at: Instant,
    ) : SessionEvent

    /** Back to the entry state; asks first when Lesson steps would be lost (FR-3). */
    data class Reset(
        override val at: Instant,
    ) : SessionEvent

    /** The Learner confirmed the [Effect.ConfirmReset] issued as [effectId] at [revision]. */
    data class ResetConfirmed(
        val effectId: Long,
        val revision: Long,
        override val at: Instant,
    ) : SessionEvent

    /** The Learner declined the [Effect.ConfirmReset] issued as [effectId] at [revision]. */
    data class ResetDeclined(
        val effectId: Long,
        val revision: Long,
        override val at: Instant,
    ) : SessionEvent

    /** The Learner chose to be taught in [mode]; the Cube State and progress stay. */
    data class TeachingModeChanged(
        val mode: TeachingMode,
        override val at: Instant,
    ) : SessionEvent

    /** The Learner asked to see the current step's Moves (Guide me "Show the Move", Hint). */
    data class RevealRequested(
        override val at: Instant,
    ) : SessionEvent

    /** The Learner moved on to the next step; applies only once the current step's path state is reached. */
    data class StepAdvanced(
        override val at: Instant,
    ) : SessionEvent
}
