package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.TutorConfig
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.MoveHistory
import dev.rubikxplorer.domain.predicate.ObjectiveProgress
import dev.rubikxplorer.domain.tutor.Assess
import dev.rubikxplorer.domain.tutor.MoveFeedback
import dev.rubikxplorer.domain.tutor.TeachingMode
import dev.rubikxplorer.domain.tutor.Tutor
import dev.rubikxplorer.domain.tutor.TutorDecision

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
 * - The tutor fields ([teachingMode], [noticeStep], [revealRequested], [lastMoveFeedback]) never
 *   advance [revision]: a mode switch or a reveal does not change the Cube State, so it must not
 *   stale a pending effect.
 * - The current step is never stored: [stepIndex] derives it from the Cube State (AD-6).
 */
@ConsistentCopyVisibility
data class SessionState internal constructor(
    val revision: Long,
    /** `null` only in [IDLE]. */
    val kind: SessionKind?,
    /** The Lesson of a [SessionKind.LESSON] session; `null` otherwise. */
    val lesson: Lesson?,
    /** The tutor configuration the Lesson started with; `null` exactly when [lesson] is. */
    val tutorConfig: TutorConfig?,
    val history: MoveHistory,
    /** The verified Solution a [SessionKind.SOLVE_PLAYBACK] session plays from [entry]; empty otherwise. */
    val solution: List<Move>,
    val objectiveMet: Boolean,
    /** `null` in [IDLE] and outside a Lesson. */
    val progress: ObjectiveProgress?,
    /** The reset confirmation awaiting the Learner, if any. */
    val pendingReset: Effect.ConfirmReset?,
    /** The id the next issued [Effect] gets; deterministic, never reused. */
    val nextEffectId: Long,
    /** The last effect result that arrived stale, if any. */
    val lastDiscarded: EffectResultDiscarded?,
    /** How the tutor teaches; kept across Lessons for the app process. */
    val teachingMode: TeachingMode,
    /**
     * A completed step whose Notice stays on the card until *Next* (`StepAdvanced`); transient, it
     * holds no current step and any change of the Cube State re-derives it.
     */
    val noticeStep: Int?,
    /** True once the Learner asked to see the current step's Moves. */
    val revealRequested: Boolean,
    /**
     * How the last committed Move changed progress toward the Objective, set by every `MoveCommitted`
     * whatever the mode (only [decision] decides whether it shows); cleared by anything else that
     * moves the cube or the step, and by a change of mode.
     */
    val lastMoveFeedback: MoveFeedback?,
) {
    /** The tutor's decision for the shown step; `null` outside a Lesson. Derived, never stored. */
    val decision: TutorDecision? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        lesson?.let { Assess.decide(it, history, checkNotNull(tutorConfig), teachingMode, revealRequested, noticeStep, lastMoveFeedback) }
    }

    /** The 0-based Lesson step the card shows ([Tutor.shownStep]); 0 outside a Lesson. Derived, never stored. */
    val stepIndex: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
        lesson?.let { Tutor.shownStep(it, entry, virtual, noticeStep) } ?: 0
    }

    /** The session's start: a Lesson's entry state (where Reset returns) or the state a Solution plays from. */
    val entry: CubeState get() = history.start

    /** The current Cube State. */
    val virtual: CubeState get() = history.state

    /** True while any session is active. */
    val isActive: Boolean get() = kind != null

    /** At least one committed, not-undone Move since the Lesson started; Reset would lose it. */
    val hasUnsavedSteps: Boolean get() = history.canUndo

    companion object {
        /** No session: every event but `LessonStarted` and `SolvePlaybackStarted` leaves it unchanged. */
        val IDLE: SessionState =
            SessionState(
                revision = 0,
                kind = null,
                lesson = null,
                tutorConfig = null,
                history = MoveHistory(),
                solution = emptyList(),
                objectiveMet = false,
                progress = null,
                pendingReset = null,
                nextEffectId = 1,
                lastDiscarded = null,
                teachingMode = TeachingMode.DEFAULT,
                noticeStep = null,
                revealRequested = false,
                lastMoveFeedback = null,
            )
    }
}
