package dev.cubentia.application.session

import dev.cubentia.domain.content.Lesson
import dev.cubentia.domain.content.TutorConfig
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.MoveHistory
import dev.cubentia.domain.predicate.ObjectiveProgress
import dev.cubentia.domain.recognition.PhysicalObservation
import dev.cubentia.domain.tutor.AdaptivePolicy
import dev.cubentia.domain.tutor.Assess
import dev.cubentia.domain.tutor.AssistanceLevel
import dev.cubentia.domain.tutor.Diagnosis
import dev.cubentia.domain.tutor.HintRung
import dev.cubentia.domain.tutor.MoveFeedback
import dev.cubentia.domain.tutor.Recovery
import dev.cubentia.domain.tutor.RecoveryGoal
import dev.cubentia.domain.tutor.RecoveryOffer
import dev.cubentia.domain.tutor.RecoveryPlanner
import dev.cubentia.domain.tutor.TeachingMode
import dev.cubentia.domain.tutor.Tutor
import dev.cubentia.domain.tutor.TutorDecision

/**
 * The state of the one `LearningSession` (AD-3), or [IDLE] when none is active. Only [reduce] makes
 * new values.
 *
 * - [revision] increases by exactly 1 whenever the session, its Cube State or its undo/redo
 *   history changes. Bookkeeping alone ([pendingReset], [nextEffectId], [lastDiscarded]) does not
 *   advance it.
 * - [history] starts at [entry], where Reset returns; [virtual] is its current state.
 *   The Tutor walks the authored path from [pathEntry].
 * - [objectiveMet] and [progress] are the Lesson's Completion Predicate on [virtual], recomputed on
 *   every change of [virtual].
 * - The tutor fields ([teachingMode], [noticeStep], [revealRequested], [hintRung], [hintsUsed],
 *   [attemptOpen], [attemptAssistance], [lastMoveFeedback], [lastDiagnosis], [guidedRecovery], [stepMistakes], [cleanSteps],
 *   [cleanCredited], [stepHelped], [assistanceDismissed], [restoreNote], [physicalSolveConfirmed], [physicalSolveUnconfirmed]) never advance [revision]: a mode switch, a reveal or a Hint
 *   does not change the Cube State, so it must not stale a pending effect.
 * - [checkpoint] is the Objective-boundary history *Try again* returns to; its changes never
 *   advance [revision] either.
 * - The current step is never stored: [stepIndex] derives it from the Cube State (AD-6), and never
 *   from [checkpoint].
 */
@ConsistentCopyVisibility
data class SessionState internal constructor(
    val revision: Long,
    /** Open Lesson run; bookkeeping never advances revision. */
    val attemptOpen: Boolean = false,
    /** Highest assistance shown during the run. */
    val attemptAssistance: AssistanceLevel = AssistanceLevel.USUAL,
    /** Transient content-update note; never snapshotted. */
    val restoreNote: dev.cubentia.domain.text.Text? = null,
    /** `null` only in [IDLE]. */
    val kind: SessionKind?,
    /** The Lesson of a [SessionKind.LESSON] session; `null` otherwise. */
    val lesson: Lesson?,
    /** The tutor configuration the Lesson started with; `null` exactly when [lesson] is. */
    val tutorConfig: TutorConfig?,
    /** Session history from the Reset target [entry]; Tutor guidance starts from [pathEntry]. */
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
    /**
     * The `ADAPTIVE_ASSISTANCE` flag the Lesson started with (`LessonStarted.adaptiveAssistance`); a
     * session field. False in [IDLE] and Solve Playback.
     */
    val adaptiveAssistance: Boolean,
    /**
     * Harmful Moves per step (FR-13): a Harmful [Assess.classify] result adds 1 to the step derived
     * before the Move. Reset only by `LessonStarted`; empty in [IDLE] and Solve Playback.
     */
    val stepMistakes: Map<Int, Int>,
    /**
     * Steps in a row completed cleanly (no mistake on the step and no Hint before its last Move); any
     * other completed step or a Harmful Move sets it to 0. Reset only by `LessonStarted`.
     */
    val cleanSteps: Int,
    /**
     * The steps whose clean completion already raised [cleanSteps]; a credited step completed cleanly
     * again (after an Undo across its boundary, a Reset or *Try again*) does not raise it twice. Reset only by `LessonStarted`.
     */
    val cleanCredited: Set<Int>,
    /**
     * The steps that used help (a Hint or a reveal) in this Lesson; such a step is never completed
     * cleanly, not even after a Reset or *Try again*. Reset only by `LessonStarted`.
     */
    val stepHelped: Set<Int>,
    /** The adaptive level *Back to my usual help* dismissed, per step. Reset only by `LessonStarted`. */
    val assistanceDismissed: Map<Int, AssistanceLevel>,
    /** The expected state of the physical cube (AD-5); mirrors [virtual] in physical mode. */
    val expected: CubeState = history.state,
    /** The latest validated observation of the physical cube (AD-5); null when unobserved or stale. */
    val observed: PhysicalObservation? = null,
    /** The current state in the 5-state sync state machine (AD-3). */
    val syncState: SyncState = SyncState.VIRTUAL,
    /**
     * True once the Learner confirmed the Objective on their real cube (`PhysicalSolveConfirmed`), while
     * it stays met; a physical session's completion is recorded only then. Never advances [revision].
     */
    val physicalSolveConfirmed: Boolean = false,
    /**
     * True while the Objective was met in physical mode and the Learner has not confirmed it, kept
     * across a switch to virtual mode, so no milestone can skip the confirmation. Never advances [revision].
     */
    val physicalSolveUnconfirmed: Boolean = false,
) {
    /** Whether the session is currently in virtual or physical mode. */
    val mode: CubeMode get() = if (syncState == SyncState.VIRTUAL) CubeMode.VIRTUAL else CubeMode.PHYSICAL

    /** Whether the session is currently in physical mode. */
    val isPhysical: Boolean get() = syncState != SyncState.VIRTUAL

    /** The tutor's decision for the shown step; `null` outside a Lesson. Derived, never stored. */
    val decision: TutorDecision? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        lesson?.let {
            Assess.decide(
                it,
                history,
                checkNotNull(tutorConfig),
                teachingMode,
                revealRequested,
                shownRung,
                noticeStep,
                lastMoveFeedback,
                lastDiagnosis,
                when (val recovery = guidedRecovery) {
                    is GuidedRecovery.Planning -> Recovery.Pending(recovery.effect.effectId)
                    is GuidedRecovery.Planned -> Recovery.Ready(recovery.moves)
                    GuidedRecovery.None, GuidedRecovery.NotFound -> Recovery.Unavailable
                },
                entry = pathEntry,
                isPhysical = syncState != SyncState.VIRTUAL,
                isMismatch = syncState == SyncState.PHYSICAL_MISMATCH,
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
        RecoveryPlanner.goal(lesson, listOfNotNull(pathEntry, checkpoint.state, history.undo().state))
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
        lesson?.let { Tutor.shownStep(it, pathEntry, virtual, noticeStep) } ?: 0
    }

    /**
     * The adaptive assistance on the shown step (FR-13): USUAL outside a Lesson, with the flag off, in
     * *Show me*, or when the Learner dismissed this level for [stepIndex]; else [AdaptivePolicy.level]
     * from the step's mistakes and [cleanSteps]. Derived, never stored.
     */
    val assistance: AssistanceLevel by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (kind == SessionKind.PRACTICE) {
            AssistanceLevel.REDUCED
        } else {
            val config = tutorConfig
            if (lesson == null || config == null || !adaptiveAssistance || teachingMode == TeachingMode.SHOW_ME) {
                AssistanceLevel.USUAL
            } else {
                val level = AdaptivePolicy.level(config.adaptive, stepMistakes[stepIndex] ?: 0, cleanSteps)
                if (assistanceDismissed[stepIndex] == level) AssistanceLevel.USUAL else level
            }
        }
    }

    /** The Hint Ladder rung the shown step acts on: [hintRung] held within [assistance]'s floor and top. Derived, never stored. */
    val shownRung: HintRung get() = hintRung.coerceAtLeast(assistance.floor).coerceAtMost(assistance.top)

    /** The session's start and Reset target, possibly a stored cube; Solution Playback also starts here. */
    val entry: CubeState get() = history.start

    /** The authored Tutor path entry in a Lesson; otherwise the session start. Never stored. */
    val pathEntry: CubeState by lazy(LazyThreadSafetyMode.PUBLICATION) {
        lesson?.let { entryState(it) } ?: history.start
    }

    /** The current Cube State. */
    val virtual: CubeState get() = history.state

    /** True while any session is active. */
    val isActive: Boolean get() = kind != null

    /** At least one committed, not-undone Move since the Lesson started; Reset would lose it. */
    val hasUnsavedSteps: Boolean get() = history.canUndo

    companion object {
        /**
         * No session: starts and restores may activate it. Ended playback uses this empty state
         * with its monotonic revision and effect ID preserved.
         */
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
                adaptiveAssistance = false,
                stepMistakes = emptyMap(),
                cleanSteps = 0,
                cleanCredited = emptySet(),
                stepHelped = emptySet(),
                assistanceDismissed = emptyMap(),
                expected = CubeState.SOLVED,
                observed = null,
                syncState = SyncState.VIRTUAL,
                physicalSolveConfirmed = false,
                physicalSolveUnconfirmed = false,
            )
    }
}
