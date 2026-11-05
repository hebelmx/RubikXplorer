package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.TutorConfig
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.tutor.TeachingMode
import kotlin.time.Instant

/**
 * Every change to a `LearningSession` (AD-3), applied by [reduce]. Each event carries the [at]
 * the session host stamped at dispatch; `session/TRANSITIONS.md` lists what each one mutates.
 */
sealed interface SessionEvent {
    val at: Instant

    data class CheckpointRestored(
        val checkpoint: Checkpoint,
        val lesson: Lesson,
        val tutorConfig: TutorConfig,
        val contentRevision: String,
        override val at: Instant,
    ) : SessionEvent

    data class CheckpointSaveFailed(
        override val at: Instant,
    ) : SessionEvent

    data class CheckpointLoadFailed(
        override val at: Instant,
    ) : SessionEvent

    data class SessionRestored(
        val snapshot: SessionSnapshot,
        val lesson: Lesson,
        val tutorConfig: TutorConfig,
        override val at: Instant,
    ) : SessionEvent

    /**
     * Starts a Lesson session at the Lesson's entry state, replacing any current session; the tutor
     * judges it with [tutorConfig] (the content bundle's `tutor-config.json`). [adaptiveAssistance] is
     * the `ADAPTIVE_ASSISTANCE` feature flag, read by the app (never by `:core:*`): off, the Hint
     * Ladder stays fixed.
     */
    data class LessonStarted(
        val lesson: Lesson,
        val tutorConfig: TutorConfig,
        override val at: Instant,
        val adaptiveAssistance: Boolean = false,
        val mode: TeachingMode? = null,
    ) : SessionEvent

    /**
     * Starts a [SessionKind.SOLVE_PLAYBACK] session that plays [solution] from [state], replacing any
     * current session. Applies only when [solution] leaves [state] solved, so an unverified list never
     * becomes a session. Playback position and speed are not part of the session (AD-20).
     */
    data class SolvePlaybackStarted(
        val state: CubeState,
        val solution: List<Move>,
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

    /** The Learner asked to see the current step's Moves (Guide me "Show the Move"); not a Hint. */
    data class RevealRequested(
        override val at: Instant,
    ) : SessionEvent

    /**
     * The Learner asked for a Hint (FR-11): climbs the shown step's Hint Ladder exactly one rung and
     * counts it, when the ladder allows a climb (`HintLadder.canClimb`).
     */
    data class HintRequested(
        override val at: Instant,
    ) : SessionEvent

    /**
     * *Back to my usual help* (FR-13): while adaptive assistance changes the shown step's help
     * ([SessionState.assistance] is not USUAL), dismisses that level for the step; a later rise to a
     * different level shows again. Otherwise nothing changes. Never changes the Teaching Mode.
     */
    data class UsualHelpRequested(
        override val at: Instant,
    ) : SessionEvent

    /**
     * *Try again* (FR-12): the Lesson's history returns to its [SessionState.checkpoint], so the
     * current step starts again from the last Objective boundary; the Lesson, the Teaching Mode and
     * the Hint count stay. Not undoable, and leaves nothing to redo.
     */
    data class RetryRequested(
        override val at: Instant,
    ) : SessionEvent

    /**
     * *Guide me back* (FR-12): while [SessionState.canGuideBack] holds, issues [Effect.PlanRecovery]
     * at the current revision and records it as Planning; otherwise nothing changes.
     */
    data class GuidedRecoveryRequested(
        override val at: Instant,
    ) : SessionEvent

    /** The [Effect.PlanRecovery] issued as [effectId] at [revision] found the correction [moves]. */
    data class RecoveryPlanned(
        val effectId: Long,
        val revision: Long,
        val moves: List<Move>,
        override val at: Instant,
    ) : SessionEvent

    /** The [Effect.PlanRecovery] issued as [effectId] at [revision] found no correction in its bound or time. */
    data class RecoveryPlanFailed(
        val effectId: Long,
        val revision: Long,
        override val at: Instant,
    ) : SessionEvent

    /**
     * Storing the session snapshot failed (AD-12, AD-16): the session is unchanged and keeps running
     * in memory; the next committed transition writes again.
     */
    data class SnapshotSaveFailed(
        override val at: Instant,
    ) : SessionEvent

    /**
     * Reading the latest session snapshot on cold start failed (AD-12, AD-16): nothing is restored
     * and the session is unchanged, so the Learner starts fresh.
     */
    data class SnapshotLoadFailed(
        override val at: Instant,
    ) : SessionEvent

    /** The Learner moved on past a completed step's Notice; applies only while one is shown and the Lesson is not complete. */
    data class StepAdvanced(
        override val at: Instant,
    ) : SessionEvent
}
