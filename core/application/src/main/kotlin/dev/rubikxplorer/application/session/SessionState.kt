package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.TutorConfig
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.MoveHistory
import dev.rubikxplorer.domain.predicate.ObjectiveProgress
import dev.rubikxplorer.domain.tutor.Assess
import dev.rubikxplorer.domain.tutor.Diagnosis
import dev.rubikxplorer.domain.tutor.HintRung
import dev.rubikxplorer.domain.tutor.MoveFeedback
import dev.rubikxplorer.domain.tutor.Recovery
import dev.rubikxplorer.domain.tutor.RecoveryGoal
import dev.rubikxplorer.domain.tutor.RecoveryOffer
import dev.rubikxplorer.domain.tutor.RecoveryPlanner
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
 * - The tutor fields ([teachingMode], [noticeStep], [revealRequested], [hintRung], [hintsUsed],
 *   [lastMoveFeedback], [lastDiagnosis], [guidedRecovery]) never advance [revision]: a mode switch, a reveal or a Hint
 *   does not change the Cube State, so it must not stale a pending effect.
 * - [checkpoint] is the Objective-boundary history *Try again* returns to; its changes never
 *   advance [revision] either.
 * - The current step is never stored: [stepIndex] derives it from the Cube State (AD-6), and never
 *   from [checkpoint].
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
     * The shown step's Hint Ladder rung (FR-11): per-step state (AD-6), reset with [revealRequested]
     * whenever the shown step changes and on a new Objective.
     */
    val hintRung: HintRung,
    /**
     * The Hints applied in this attempt: 0 at `LessonStarted`, raised by 1 per applied
     * `HintRequested`, lowered by nothing else (persisted by Story 6.3).
     */
    val hintsUsed: Int,
    /**
     * How the last committed Move related to the current step ([Assess.classify]), set by a
     * `MoveCommitted` in *Test me* only (only [decision] decides whether it shows); cleared by a commit
     * in any other mode, by anything else that moves the cube or the step, and by a change of mode.
     */
    val lastMoveFeedback: MoveFeedback?,
    /** The mistake [Assess.classify] named for the last committed Move, if any; set and cleared with [lastMoveFeedback]. */
    val lastDiagnosis: Diagnosis?,
    /**
     * The Checkpoint (FR-12): the history at the last Objective boundary, which *Try again*
     * (`RetryRequested`) restores. In a Lesson it is the entry history after `LessonStarted` or an
     * applied Reset, then the history wherever a step's Notice became pending or the Lesson became
     * complete, always with nothing to redo; a Move that disturbs preserved pieces never moves it.
     * It holds a history, never the current step (AD-6). `null` in [IDLE] and Solve Playback (AD-20).
     */
    val checkpoint: MoveHistory?,
    /**
     * The Guided recovery (FR-12): [GuidedRecovery.None] until *Guide me back*, then Planning while the
     * [Effect.PlanRecovery] runs, then Planned or NotFound. Any history change and any other non-commit
     * Lesson event return it to None (AD-3 cancels the effect); following a plan's next Move keeps it.
     */
    val guidedRecovery: GuidedRecovery,
) {
    /** The tutor's decision for the shown step; `null` outside a Lesson. Derived, never stored. */
    val decision: TutorDecision? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        lesson?.let {
            Assess.decide(
                it,
                history,
                checkNotNull(tutorConfig),
                teachingMode,
                revealRequested,
                hintRung,
                noticeStep,
                lastMoveFeedback,
                lastDiagnosis,
                when (val recovery = guidedRecovery) {
                    is GuidedRecovery.Planning -> Recovery.Pending(recovery.effect.effectId)
                    is GuidedRecovery.Planned -> Recovery.Ready(recovery.moves)
                    GuidedRecovery.None, GuidedRecovery.NotFound -> Recovery.Unavailable
                },
            )
        }
    }

    /**
     * What a Guided recovery restores now ([RecoveryPlanner.goal]): the preserved pieces at the
     * Checkpoint and just before the last Move; `null` outside a Lesson. Derived, never stored.
     */
    val recoveryGoal: RecoveryGoal? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val lesson = lesson ?: return@lazy null
        val checkpoint = checkpoint ?: return@lazy null
        RecoveryPlanner.goal(lesson, listOf(checkpoint.state, history.undo().state))
    }

    /**
     * True when *Guide me back* applies: a Lesson whose shown Diagnosis offers it, no Guided recovery
     * under way or shown, a Checkpoint, and preserved pieces still to restore on [virtual].
     */
    val canGuideBack: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        kind == SessionKind.LESSON &&
            guidedRecovery == GuidedRecovery.None &&
            checkpoint != null &&
            decision
                ?.diagnosis
                ?.recoveryPath
                ?.offers
                ?.contains(RecoveryOffer.GUIDE_ME_BACK) == true &&
            recoveryGoal?.isMet(virtual) == false
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
                hintRung = HintRung.NONE,
                hintsUsed = 0,
                lastMoveFeedback = null,
                lastDiagnosis = null,
                checkpoint = null,
                guidedRecovery = GuidedRecovery.None,
            )
    }
}
