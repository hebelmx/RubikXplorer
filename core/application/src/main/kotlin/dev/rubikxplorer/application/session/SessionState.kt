package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.MoveHistory
import dev.rubikxplorer.domain.predicate.ObjectiveProgress

/**
 * The state of the one `LearningSession` (AD-3), or [IDLE] when none is active. Only [reduce] makes
 * new values.
 *
 * - [revision] increases by exactly 1 whenever the session, its Cube State or its undo/redo
 *   history changes. Bookkeeping alone ([pendingReset], [nextEffectId], [lastDiscarded]) does not
 *   advance it.
 * - [history] starts at [entry]; [virtual] is its current state.
 * - [objectiveMet] and [progress] are the Lesson's Completion Predicate on [virtual], recomputed on
 *   every change of [virtual].
 */
@ConsistentCopyVisibility
data class SessionState internal constructor(
    val revision: Long,
    /** `null` only in [IDLE]. */
    val kind: SessionKind?,
    /** `null` only in [IDLE]. */
    val lesson: Lesson?,
    val history: MoveHistory,
    val objectiveMet: Boolean,
    /** `null` only in [IDLE]. */
    val progress: ObjectiveProgress?,
    /** The reset confirmation awaiting the Learner, if any. */
    val pendingReset: Effect.ConfirmReset?,
    /** The id the next issued [Effect] gets; deterministic, never reused. */
    val nextEffectId: Long,
    /** The last effect result that arrived stale, if any. */
    val lastDiscarded: EffectResultDiscarded?,
) {
    /** The Lesson's entry state; where Reset returns. */
    val entry: CubeState get() = history.start

    /** The current Cube State. */
    val virtual: CubeState get() = history.state

    /** True while a Lesson session is active. */
    val isActive: Boolean get() = lesson != null

    /** At least one committed, not-undone Move since the Lesson started; Reset would lose it. */
    val hasUnsavedSteps: Boolean get() = history.canUndo

    companion object {
        /** No session: every event but `LessonStarted` leaves it unchanged. */
        val IDLE: SessionState =
            SessionState(
                revision = 0,
                kind = null,
                lesson = null,
                history = MoveHistory(),
                objectiveMet = false,
                progress = null,
                pendingReset = null,
                nextEffectId = 1,
                lastDiscarded = null,
            )
    }
}
