package dev.cubentia.application.session

import dev.cubentia.application.preference.Preference
import dev.cubentia.domain.content.FaceletString
import dev.cubentia.domain.content.FaceletStringResult
import dev.cubentia.domain.content.Lesson
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.MoveHistory
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.recognition.PhysicalObservation
import dev.cubentia.domain.text.Text
import dev.cubentia.domain.tutor.Assess
import dev.cubentia.domain.tutor.AssistanceLevel
import dev.cubentia.domain.tutor.Diagnosis
import dev.cubentia.domain.tutor.ErrorCategory
import dev.cubentia.domain.tutor.HintLadder
import dev.cubentia.domain.tutor.HintRung
import dev.cubentia.domain.tutor.MoveAssessment
import dev.cubentia.domain.tutor.MoveFeedback
import dev.cubentia.domain.tutor.RecoveryPlanner
import dev.cubentia.domain.tutor.TeachingMode
import dev.cubentia.domain.tutor.Tutor

/**
 * The pure `LearningSession` reducer (AD-3): no I/O, no wall clock, no randomness, bounded work.
 * `session/TRANSITIONS.md` is the normative table of what each [SessionEvent] mutates.
 */
fun reduce(
    state: SessionState,
    event: SessionEvent,
): Transition {
    if (event is SessionEvent.CheckpointRestored) {
        return attempts(
            state,
            persistCheckpoint(state, restoreCheckpoint(state, event), event),
            event,
        )
    }
    if (event is SessionEvent.SessionRestored) return restore(state, event)
    // Persistence failures are reported, never applied: the session and its feedback stay as they are.
    if (event is SessionEvent.SnapshotSaveFailed || event is SessionEvent.SnapshotLoadFailed ||
        event is SessionEvent.CheckpointSaveFailed || event is SessionEvent.CheckpointLoadFailed
    ) {
        return Transition(state)
    }
    if (event is SessionEvent.SolvePlaybackEnded) {
        return if (state.kind == SessionKind.SOLVE_PLAYBACK) {
            Transition(SessionState.IDLE.copy(revision = state.revision + 1, nextEffectId = state.nextEffectId))
        } else {
            Transition(state)
        }
    }
    val starts = event is SessionEvent.LessonStarted || event is SessionEvent.PracticeStarted || event is SessionEvent.SolvePlaybackStarted
    // Only a Lesson or Practice session takes Moves and tutor events; idle and playback sessions ignore them,
    // except SOLVE_PLAYBACK which permits ModeSwitched, PhysicalConfirmed, and Undo in physical mode (Story 8.5).
    val playbackAllowed =
        state.kind == SessionKind.SOLVE_PLAYBACK &&
            (
                event is SessionEvent.ModeSwitched || event is SessionEvent.PhysicalConfirmed ||
                    (event is SessionEvent.Undo && state.isPhysical)
            )
    if (!starts && state.kind != SessionKind.LESSON && state.kind != SessionKind.PRACTICE && !playbackAllowed) return Transition(state)
    val transition =
        attempts(state, persistCheckpoint(state, transition(state, event), event), event).let {
            if (it.state.restoreNote == null ||
                event is SessionEvent.ModeSwitched ||
                (event is SessionEvent.SolvePlaybackStarted && it.state === state)
            ) {
                it
            } else {
                it.copy(state = it.state.copy(restoreNote = null))
            }
        }
    // AD-5: feedback and its Diagnosis belong to the last committed Move; every other Lesson event
    // clears them and the Guided recovery. An unverified playback start changes nothing.
    // The Guided recovery events act on that Diagnosis, so they keep it.
    val recovery =
        event is SessionEvent.GuidedRecoveryRequested || event is SessionEvent.RecoveryPlanned || event is SessionEvent.RecoveryPlanFailed
    val keeps =
        event is SessionEvent.MoveCommitted || (event is SessionEvent.PhysicalConfirmed && event.move != null) ||
            event is SessionEvent.EditorEntered || event is SessionEvent.SolvePlaybackStarted || recovery
    if (keeps || (transition.state.kind != SessionKind.LESSON && transition.state.kind != SessionKind.PRACTICE)) return transition
    return transition.copy(
        state = transition.state.copy(lastMoveFeedback = null, lastDiagnosis = null, guidedRecovery = GuidedRecovery.None),
    )
}

private fun transition(
    state: SessionState,
    event: SessionEvent,
): Transition =
    when (event) {
        is SessionEvent.CheckpointRestored -> {
            restoreCheckpoint(state, event)
        }

        is SessionEvent.SessionRestored -> {
            restore(state, event)
        }

        is SessionEvent.LessonStarted -> {
            start(state, event)
        }

        is SessionEvent.PracticeStarted -> {
            startPractice(state, event)
        }

        is SessionEvent.SolvePlaybackStarted -> {
            startPlayback(state, event.state, event.solution)
        }

        is SessionEvent.SolvePlaybackEnded -> {
            Transition(state)
        }

        is SessionEvent.MoveCommitted -> {
            if (state.syncState == SyncState.VIRTUAL) committed(state, event.move) else Transition(state)
        }

        is SessionEvent.Undo -> {
            undo(state)
        }

        is SessionEvent.Redo -> {
            if (state.syncState == SyncState.VIRTUAL) {
                state.history.redoable?.let { move(state, state.history.redo(), it, null) } ?: Transition(state)
            } else {
                state.history.redoable?.let { confirmPhysical(state, SessionEvent.PhysicalConfirmed(it, event.at), isRedo = true) }
                    ?: Transition(state)
            }
        }

        is SessionEvent.Reset -> {
            if (state.syncState == SyncState.VIRTUAL) {
                if (state.hasUnsavedSteps) askReset(state) else reset(state)
            } else {
                Transition(state)
            }
        }

        is SessionEvent.ResetConfirmed -> {
            confirmed(state, event)
        }

        is SessionEvent.ResetDeclined -> {
            declined(state, event)
        }

        is SessionEvent.TeachingModeChanged -> {
            modeChanged(state, event)
        }

        is SessionEvent.RevealRequested -> {
            Transition(state.copy(revealRequested = true, stepHelped = state.stepHelped + state.stepIndex))
        }

        is SessionEvent.HintRequested -> {
            hint(state)
        }

        is SessionEvent.UsualHelpRequested -> {
            usualHelp(state)
        }

        is SessionEvent.StepAdvanced -> {
            advance(state)
        }

        is SessionEvent.RetryRequested -> {
            if (state.syncState == SyncState.VIRTUAL) retry(state) else Transition(state)
        }

        is SessionEvent.GuidedRecoveryRequested -> {
            guideBack(state)
        }

        is SessionEvent.RecoveryPlanned -> {
            planned(state, event)
        }

        is SessionEvent.RecoveryPlanFailed -> {
            planFailed(state, event)
        }

        is SessionEvent.ModeSwitched -> {
            switchMode(state, event)
        }

        is SessionEvent.PhysicalConfirmed -> {
            confirmPhysical(state, event)
        }

        is SessionEvent.MismatchReported -> {
            reportMismatch(state, event)
        }

        is SessionEvent.EditorEntered -> {
            enterEditor(state, event)
        }

        is SessionEvent.CheckpointSaveFailed, is SessionEvent.CheckpointLoadFailed,
        is SessionEvent.SnapshotSaveFailed, is SessionEvent.SnapshotLoadFailed,
        -> {
            Transition(state)
        }
    }

private fun start(
    state: SessionState,
    event: SessionEvent.LessonStarted,
): Transition {
    val lesson = event.lesson
    val entry = entryState(lesson)
    val next =
        withHistory(state, MoveHistory(entry), lesson).copy(
            kind = SessionKind.LESSON,
            lesson = lesson,
            tutorConfig = event.tutorConfig,
            adaptiveAssistance = event.adaptiveAssistance,
            teachingMode = event.mode ?: state.teachingMode,
            stepMistakes = emptyMap(),
            cleanSteps = 0,
            cleanCredited = emptySet(),
            stepHelped = emptySet(),
            assistanceDismissed = emptyMap(),
            solution = emptyList(),
            lastDiscarded = null,
            noticeStep = null,
            revealRequested = false,
            hintRung = HintRung.NONE,
            hintsUsed = 0,
            attemptOpen = false,
            attemptAssistance = AssistanceLevel.USUAL,
            lastMoveFeedback = null,
            lastDiagnosis = null,
            checkpoint = MoveHistory(entry),
            guidedRecovery = GuidedRecovery.None,
            expected = entry,
            observed = null,
            syncState = SyncState.VIRTUAL,
        )
    return Transition(next, presentation = listOf(Presentation.Snap(next.revision, entry)))
}

private fun startPractice(
    state: SessionState,
    event: SessionEvent.PracticeStarted,
): Transition {
    val lesson = event.lesson
    val entry = entryState(lesson)
    val next =
        withHistory(state, MoveHistory(entry), lesson).copy(
            kind = SessionKind.PRACTICE,
            lesson = lesson,
            tutorConfig = event.tutorConfig,
            adaptiveAssistance = false,
            teachingMode = state.teachingMode,
            stepMistakes = emptyMap(),
            cleanSteps = 0,
            cleanCredited = emptySet(),
            stepHelped = emptySet(),
            assistanceDismissed = emptyMap(),
            solution = emptyList(),
            lastDiscarded = null,
            noticeStep = null,
            revealRequested = false,
            hintRung = HintRung.NONE,
            hintsUsed = 0,
            attemptOpen = false,
            attemptAssistance = AssistanceLevel.REDUCED,
            lastMoveFeedback = null,
            lastDiagnosis = null,
            checkpoint = MoveHistory(entry),
            guidedRecovery = GuidedRecovery.None,
            expected = entry,
            observed = null,
            syncState = SyncState.VIRTUAL,
        )
    return Transition(next, presentation = listOf(Presentation.Snap(next.revision, entry)))
}

/**
 * Starts a Solve Playback session at [start] holding [solution], when [solution] verifiably solves
 * [start] (bounded: one pass over the list); otherwise nothing changes. The Teaching Mode is kept.
 * An active Lesson is suspended; it resumes separately from its stored Checkpoint (AD-3).
 */
private fun startPlayback(
    state: SessionState,
    start: CubeState,
    solution: List<Move>,
): Transition {
    if (!start.apply(solution).isSolved) return Transition(state)
    val next =
        withHistory(state, MoveHistory(start), lesson = null).copy(
            kind = SessionKind.SOLVE_PLAYBACK,
            lesson = null,
            tutorConfig = null,
            adaptiveAssistance = false,
            stepMistakes = emptyMap(),
            cleanSteps = 0,
            cleanCredited = emptySet(),
            stepHelped = emptySet(),
            assistanceDismissed = emptyMap(),
            solution = solution.toList(),
            lastDiscarded = null,
            noticeStep = null,
            revealRequested = false,
            hintRung = HintRung.NONE,
            hintsUsed = 0,
            attemptOpen = false,
            attemptAssistance = AssistanceLevel.USUAL,
            lastMoveFeedback = null,
            lastDiagnosis = null,
            checkpoint = null,
            guidedRecovery = GuidedRecovery.None,
            expected = start,
            observed = null,
            syncState = SyncState.VIRTUAL,
        )
    return Transition(next, presentation = listOf(Presentation.Snap(next.revision, start)))
}

/** Loaded content is validated (Story 2.1), so an undecodable entry state is a programmer error. */
internal fun entryState(lesson: Lesson): CubeState {
    val decoded = FaceletString.decode(lesson.entryState)
    check(decoded is FaceletStringResult.Decoded) { "Lesson ${lesson.id} has a malformed entry state: $decoded" }
    val validated = CubeValidator.validate(decoded.facelets)
    check(validated is ValidationResult.Valid) { "Lesson ${lesson.id} has an impossible entry state: $validated" }
    return validated.state
}

/**
 * Commits [move]. [Assess.classify] judges it against the step derived from the state before it
 * (AD-5) in *Test me*, and also in *Guide me* and *Let me try* while adaptive assistance is on; only
 * *Test me* keeps its feedback and Diagnosis, which any other mode clears. The result feeds adaptive
 * assistance ([adapted]). A [move] that is the next Move of a Planned Guided recovery keeps the plan
 * with the Moves left (none left: no Guided recovery); any other Move ends it.
 */
private fun committed(
    state: SessionState,
    move: Move,
): Transition {
    val history = state.history.commit(move)
    val lesson = state.lesson
    val mode = state.teachingMode
    val assessed =
        state.kind == SessionKind.PRACTICE || mode == TeachingMode.TEST_ME || (state.adaptiveAssistance && mode in ADAPTIVE_MODES)
    val assessment =
        if (lesson != null && assessed) {
            Assess.classify(lesson, state.pathEntry, state.virtual, move, history.state, checkNotNull(state.tutorConfig))
        } else {
            null
        }
    val moved = move(state, history, move, assessment.takeIf { mode == TeachingMode.TEST_ME })
    val counted = lesson?.let { moved.copy(state = adapted(state, moved.state, it, assessment?.feedback == MoveFeedback.HARMFUL)) } ?: moved
    val plan = state.guidedRecovery as? GuidedRecovery.Planned
    if (plan == null || plan.moves.firstOrNull() != move) return counted
    val left = plan.moves.drop(1)
    val following = if (left.isEmpty()) GuidedRecovery.None else plan.copy(moves = left, text = RecoveryPlanner.describe(left, plan.pieces))
    return counted.copy(state = counted.state.copy(guidedRecovery = following))
}

/** The modes besides *Test me* whose Moves adaptive assistance assesses. */
private val ADAPTIVE_MODES = setOf(TeachingMode.GUIDE_ME, TeachingMode.LET_ME_TRY)

/**
 * Adaptive assistance's counts (FR-13) after a commit from [before] to [moved]: a [harmful] Move adds 1
 * to the mistakes of the step derived before it and ends the clean run. A commit that makes a Notice
 * newly pending or newly completes the Lesson completes `s0` (the step shown before): clean when `s0`
 * has no mistakes, [before] had no Hint rung and no revealed Moves, and `s0` used no help in this Lesson
 * ([SessionState.stepHelped], kept across Reset and *Try again*), which raises the clean run by 1 the
 * first time `s0` is credited (a credited step completed cleanly again leaves the run); else the run is 0.
 */
private fun adapted(
    before: SessionState,
    moved: SessionState,
    lesson: Lesson,
    harmful: Boolean,
): SessionState {
    val mistakes =
        if (harmful) {
            val step = Tutor.currentStep(lesson, before.pathEntry, before.virtual)
            before.stepMistakes + (step to (before.stepMistakes[step] ?: 0) + 1)
        } else {
            before.stepMistakes
        }
    val completed =
        (moved.noticeStep != null && moved.noticeStep != before.noticeStep) || (moved.objectiveMet && !before.objectiveMet)
    val s0 = before.stepIndex
    val helped = s0 in before.stepHelped || before.hintRung != HintRung.NONE || before.revealRequested
    val clean = completed && (mistakes[s0] ?: 0) == 0 && !helped
    val cleanSteps =
        when {
            harmful -> 0

            !completed -> before.cleanSteps

            // A step is credited once per Lesson: completing it again (say after an Undo across its boundary) masters nothing new.
            clean && s0 in before.cleanCredited -> before.cleanSteps

            clean -> before.cleanSteps + 1

            else -> 0
        }
    val credited = if (clean) before.cleanCredited + s0 else before.cleanCredited
    return moved.copy(stepMistakes = mistakes, cleanSteps = cleanSteps, cleanCredited = credited)
}

/**
 * [state] moved to [history], animating [animate]; [assessment] becomes the last Move's feedback and
 * Diagnosis (both cleared when it is null). With
 * `s0` the step shown before and `d1` the current step after: when `d1 > s0` and the Lesson is not
 * complete, `s0`'s Notice stays until *Next* (no auto-advance past a Notice); otherwise no Notice is
 * pending. The reveal resets whenever the shown step changes. A result with a pending Notice or a
 * complete Lesson is an Objective boundary and becomes the Checkpoint (redo dropped).
 */
private fun move(
    state: SessionState,
    history: MoveHistory,
    animate: Move,
    assessment: MoveAssessment?,
): Transition {
    val moved = withHistory(state, history).copy(lastMoveFeedback = assessment?.feedback, lastDiagnosis = assessment?.diagnosis)
    val noticed = moved.lesson?.let { lesson -> withNotice(state, moved, lesson) } ?: moved
    val boundary = noticed.lesson != null && (noticed.noticeStep != null || noticed.objectiveMet)
    val next = if (boundary) noticed.copy(checkpoint = noticed.history.withoutRedo()) else noticed
    return Transition(next, presentation = listOf(Presentation.Animate(animate, next.revision, next.virtual)))
}

private fun undo(state: SessionState): Transition {
    val undoable = state.history.undoable ?: return Transition(state)
    return if (state.syncState == SyncState.VIRTUAL) {
        move(state, state.history.undo(), undoable.inverse(), null)
    } else {
        val nextHistory = state.history.undo()
        val nextExpected = nextHistory.state
        val inverse = undoable.inverse()
        val moved =
            withHistory(state, nextHistory).copy(
                expected = nextExpected,
                observed = null,
                syncState = SyncState.PHYSICAL_PREDICTED,
                lastMoveFeedback = null,
                lastDiagnosis = null,
            )
        val noticed = moved.lesson?.let { lesson -> withNotice(state, moved, lesson) } ?: moved
        val boundary = noticed.lesson != null && (noticed.noticeStep != null || noticed.objectiveMet)
        val next = if (boundary) noticed.copy(checkpoint = noticed.history.withoutRedo()) else noticed
        val instruct = Presentation.Instruct.of(inverse, next.revision, next.virtual)
        Transition(next, presentation = listOf(instruct))
    }
}

private fun switchMode(
    state: SessionState,
    event: SessionEvent.ModeSwitched,
): Transition {
    val currentMode = if (state.syncState == SyncState.VIRTUAL) CubeMode.VIRTUAL else CubeMode.PHYSICAL
    if (event.mode == currentMode) return Transition(state)
    return when (event.mode) {
        CubeMode.PHYSICAL -> {
            val nextHistory = MoveHistory(state.virtual)
            val next =
                withHistory(state, nextHistory).copy(
                    expected = state.virtual,
                    syncState =
                        if (state.kind ==
                            SessionKind.SOLVE_PLAYBACK
                        ) {
                            SyncState.PHYSICAL_PREDICTED
                        } else {
                            SyncState.PHYSICAL_AWAITING_CONFIRM
                        },
                    restoreNote = null,
                    noticeStep = null,
                    lastMoveFeedback = null,
                    lastDiagnosis = null,
                    guidedRecovery = GuidedRecovery.None,
                )
            Transition(next, presentation = listOf(Presentation.Snap(next.revision, next.virtual)))
        }

        CubeMode.VIRTUAL -> {
            val nextHistory = MoveHistory(state.expected)
            val next =
                withHistory(state, nextHistory).copy(
                    syncState = SyncState.VIRTUAL,
                    restoreNote = Text("msg.physical.back-to-virtual"),
                    observed = null,
                    noticeStep = null,
                    lastMoveFeedback = null,
                    lastDiagnosis = null,
                    guidedRecovery = GuidedRecovery.None,
                )
            Transition(next, presentation = listOf(Presentation.Snap(next.revision, next.virtual)))
        }
    }
}

private fun confirmPhysical(
    state: SessionState,
    event: SessionEvent.PhysicalConfirmed,
    isRedo: Boolean = false,
): Transition {
    return when (state.syncState) {
        SyncState.PHYSICAL_AWAITING_CONFIRM -> {
            if (event.move == null) {
                Transition(state.copy(syncState = SyncState.PHYSICAL_PREDICTED))
            } else {
                Transition(state)
            }
        }

        SyncState.PHYSICAL_PREDICTED, SyncState.PHYSICAL_VERIFIED -> {
            val move = event.move ?: return Transition(state)
            val nextExpected = state.expected.apply(move)
            val history = if (isRedo) state.history.redo() else state.history.commit(move)
            val lesson = state.lesson
            val mode = state.teachingMode
            val assessed =
                state.kind == SessionKind.PRACTICE || mode == TeachingMode.TEST_ME || (state.adaptiveAssistance && mode in ADAPTIVE_MODES)
            val assessment =
                if (lesson != null && assessed) {
                    Assess.classify(lesson, state.pathEntry, state.virtual, move, history.state, checkNotNull(state.tutorConfig))
                } else {
                    null
                }
            val moved =
                withHistory(state, history).copy(
                    expected = nextExpected,
                    observed = null,
                    syncState = SyncState.PHYSICAL_PREDICTED,
                    lastMoveFeedback = assessment?.feedback?.takeIf { mode == TeachingMode.TEST_ME },
                    lastDiagnosis = assessment?.diagnosis?.takeIf { mode == TeachingMode.TEST_ME },
                )
            val noticed = moved.lesson?.let { l -> withNotice(state, moved, l) } ?: moved
            val boundary = noticed.lesson != null && (noticed.noticeStep != null || noticed.objectiveMet)
            val next = if (boundary) noticed.copy(checkpoint = noticed.history.withoutRedo()) else noticed
            val counted = lesson?.let { l -> adapted(state, next, l, assessment?.feedback == MoveFeedback.HARMFUL) } ?: next
            Transition(counted, presentation = listOf(Presentation.Animate(move, counted.revision, counted.virtual)))
        }

        SyncState.PHYSICAL_MISMATCH -> {
            val move = event.move ?: return Transition(state)
            val lesson = state.lesson
            val config = state.tutorConfig
            val stateBefore =
                state.history
                    .undo()
                    .state
                    .takeIf { state.history.canUndo } ?: state.expected
            val diagnosis =
                if (lesson != null && config != null) {
                    Assess.diagnosePhysicalMove(lesson, state.pathEntry, state.expected, stateBefore, move, config)
                } else {
                    null
                }
            Transition(state.copy(lastDiagnosis = diagnosis))
        }

        SyncState.VIRTUAL -> {
            Transition(state)
        }
    }
}

private fun reportMismatch(
    state: SessionState,
    event: SessionEvent.MismatchReported,
): Transition =
    if (state.syncState == SyncState.PHYSICAL_PREDICTED || state.syncState == SyncState.PHYSICAL_VERIFIED) {
        Transition(
            state.copy(
                syncState = SyncState.PHYSICAL_MISMATCH,
                lastMoveFeedback = null,
                lastDiagnosis = null,
            ),
        )
    } else {
        Transition(state)
    }

private fun enterEditor(
    state: SessionState,
    event: SessionEvent.EditorEntered,
): Transition {
    if (state.syncState == SyncState.VIRTUAL) return Transition(state)
    val nextHistory = MoveHistory(event.state)
    val lesson = state.lesson
    val config = state.tutorConfig
    val previousCheckpoint = state.checkpoint
    val diagnosis =
        if (lesson != null && config != null && !lesson.completion.isSatisfied(event.state)) {
            val expectedBase = previousCheckpoint?.state ?: state.pathEntry
            Assess.diagnose(lesson, state.pathEntry, expectedBase, event.state, config)
                ?: Assess.unknown(ErrorCategory.UNKNOWN_DISCREPANCY, Text("msg.diagnosis.unknown-discrepancy"))
        } else {
            null
        }
    val moved =
        withHistory(state, nextHistory).copy(
            expected = event.state,
            observed = PhysicalObservation.Full(event.state),
            syncState = SyncState.PHYSICAL_PREDICTED,
            lastMoveFeedback = null,
            lastDiagnosis = diagnosis,
            checkpoint = nextHistory,
        )
    val next = lesson?.let { l -> withNotice(state, moved, l) } ?: moved
    return Transition(next, presentation = listOf(Presentation.Snap(next.revision, next.virtual)))
}

private fun withNotice(
    before: SessionState,
    moved: SessionState,
    lesson: Lesson,
): SessionState {
    val shownBefore = before.stepIndex
    val current = Tutor.currentStep(lesson, moved.pathEntry, moved.virtual)
    // Only a step that is done now (effective done, AD-6) earns its Notice: a return onto the path mid-step may not redo earlier steps.
    val notice = shownBefore.takeIf { current > it && !moved.objectiveMet && Tutor.stepDone(lesson, it, moved.virtual) }
    val next = moved.copy(noticeStep = notice)
    return if (next.stepIndex == shownBefore) next else next.copy(revealRequested = false, hintRung = HintRung.NONE)
}

/**
 * Back to the entry state and the first step with nothing to undo or redo; the Checkpoint returns to
 * the entry too. Advances [SessionState.revision] only if the history changes.
 */
private fun reset(state: SessionState): Transition {
    val history = state.history.reset()
    val next =
        (if (history == state.history) state.copy(pendingReset = null) else withHistory(state, history))
            .copy(
                noticeStep = null,
                revealRequested = false,
                hintRung = HintRung.NONE,
                lastMoveFeedback = null,
                lastDiagnosis = null,
                checkpoint = history,
            )
    return Transition(next, presentation = listOf(Presentation.Snap(next.revision, next.entry)))
}

/**
 * *Try again* (FR-12): when the history differs from the Checkpoint, it becomes the Checkpoint (the
 * next revision, any pending confirmation cancelled), the shown step's Notice, reveal and Hint rung
 * reset, and the net snaps to the Checkpoint's state; the Lesson, Teaching Mode, [SessionState.hintsUsed]
 * and the Checkpoint itself stay. Otherwise nothing changes.
 */
private fun retry(state: SessionState): Transition {
    val checkpoint = state.checkpoint ?: return Transition(state)
    if (checkpoint == state.history) return Transition(state)
    val next = withHistory(state, checkpoint).copy(noticeStep = null, revealRequested = false, hintRung = HintRung.NONE)
    return Transition(next, presentation = listOf(Presentation.Snap(next.revision, next.virtual)))
}

/** Issues a reset confirmation at the current revision, warning that Lesson steps would be lost; nothing else changes. */
private fun askReset(state: SessionState): Transition {
    val effect = Effect.ConfirmReset(state.nextEffectId, state.revision, Text("msg.session.reset-unsaved"))
    return Transition(state.copy(pendingReset = effect, nextEffectId = state.nextEffectId + 1), effects = listOf(effect))
}

private fun isCurrent(
    state: SessionState,
    effectId: Long,
    revision: Long,
): Boolean {
    val pending = state.pendingReset ?: return false
    return pending.effectId == effectId && pending.revision == revision && revision == state.revision
}

private fun discarded(
    state: SessionState,
    effectId: Long,
    revision: Long,
): SessionState = state.copy(lastDiscarded = EffectResultDiscarded(effectId, revision, state.revision))

private fun confirmed(
    state: SessionState,
    event: SessionEvent.ResetConfirmed,
): Transition {
    if (isCurrent(state, event.effectId, event.revision)) return reset(state)
    val next = discarded(state, event.effectId, event.revision)
    // AD-3: the Learner started this Reset, so a confirmation overtaken by a newer revision is offered again.
    val overtaken = event.revision < state.revision
    return if (overtaken && next.syncState == SyncState.VIRTUAL && next.hasUnsavedSteps &&
        next.pendingReset == null
    ) {
        askReset(next)
    } else {
        Transition(next)
    }
}

private fun declined(
    state: SessionState,
    event: SessionEvent.ResetDeclined,
): Transition =
    if (isCurrent(state, event.effectId, event.revision)) {
        Transition(state.copy(pendingReset = null))
    } else {
        Transition(discarded(state, event.effectId, event.revision))
    }

/**
 * *Guide me back* (FR-12): while [SessionState.canGuideBack] holds, issues [Effect.PlanRecovery] for
 * the current Cube State and goal at the current revision, and records it as Planning; nothing else
 * changes. Otherwise nothing changes at all.
 */
private fun guideBack(state: SessionState): Transition {
    if (!state.canGuideBack) return Transition(state)
    val config = checkNotNull(state.tutorConfig)
    val effect =
        Effect.PlanRecovery(
            effectId = state.nextEffectId,
            revision = state.revision,
            state = state.virtual,
            goal = checkNotNull(state.recoveryGoal),
            maxDepth = config.recoveryMaxDepth,
            moveSet = config.moves,
        )
    val next = state.copy(guidedRecovery = GuidedRecovery.Planning(effect), nextEffectId = state.nextEffectId + 1)
    return Transition(next, effects = listOf(effect))
}

/** The Planning recovery [effectId] issued at [revision] is the one awaited, at the current revision. */
private fun isCurrentPlan(
    state: SessionState,
    effectId: Long,
    revision: Long,
): GuidedRecovery.Planning? {
    val planning = state.guidedRecovery as? GuidedRecovery.Planning ?: return null
    val current = planning.effect.effectId == effectId && planning.effect.revision == revision && revision == state.revision
    return planning.takeIf { current }
}

/**
 * A stale plan result: recorded as discarded and, when a newer revision overtook it, offered again
 * (a fresh [Effect.PlanRecovery]) while *Guide me back* still applies (AD-3, like a stale Reset).
 */
private fun stalePlan(
    state: SessionState,
    effectId: Long,
    revision: Long,
): Transition {
    val next = discarded(state, effectId, revision)
    return if (revision < state.revision && next.canGuideBack) guideBack(next) else Transition(next)
}

/**
 * The current plan's [SessionEvent.RecoveryPlanned.moves], verified once more on the Cube State: when
 * they meet the goal it is Planned with its copy, else NotFound. A stale result is [stalePlan].
 */
private fun planned(
    state: SessionState,
    event: SessionEvent.RecoveryPlanned,
): Transition {
    val planning = isCurrentPlan(state, event.effectId, event.revision) ?: return stalePlan(state, event.effectId, event.revision)
    val goal = planning.effect.goal
    val result =
        if (goal.isMet(state.virtual.apply(event.moves))) {
            GuidedRecovery.Planned(event.moves.toList(), goal.pieces, RecoveryPlanner.describe(event.moves, goal.pieces))
        } else {
            GuidedRecovery.NotFound
        }
    return Transition(state.copy(guidedRecovery = result))
}

/** The current plan found nothing: NotFound. A stale result is [stalePlan]. */
private fun planFailed(
    state: SessionState,
    event: SessionEvent.RecoveryPlanFailed,
): Transition {
    isCurrentPlan(state, event.effectId, event.revision) ?: return stalePlan(state, event.effectId, event.revision)
    return Transition(state.copy(guidedRecovery = GuidedRecovery.NotFound))
}

/**
 * Past a completed step's Notice, when the tutor says `advance` and the Lesson is not complete: the
 * Notice clears, so the card shows the step derived from the cube, with its Moves hidden again.
 * Otherwise nothing changes.
 */
private fun advance(state: SessionState): Transition {
    val decision = state.decision ?: return Transition(state)
    val applies = decision.advance && !decision.satisfied
    val next =
        state.copy(noticeStep = null, revealRequested = false, hintRung = HintRung.NONE, lastMoveFeedback = null, lastDiagnosis = null)
    return Transition(if (applies) next else state)
}

/**
 * A Hint (FR-11): when [HintLadder.canClimb] allows it (not in *Show me*, below the ladder's top, which
 * adaptive assistance may lower, and the step not done), the shown rung ([SessionState.shownRung])
 * climbs exactly one and the attempt's count rises by one; otherwise nothing changes. The Cube State and [SessionState.revision] stay.
 */
private fun hint(state: SessionState): Transition {
    val decision = state.decision ?: return Transition(state)
    val rung = state.shownRung
    if (!HintLadder.canClimb(state.teachingMode, rung, decision.advance, state.assistance.top)) return Transition(state)
    return Transition(state.copy(hintRung = rung.next(), hintsUsed = state.hintsUsed + 1, stepHelped = state.stepHelped + state.stepIndex))
}

/**
 * *Back to my usual help* (FR-13): while the shown step has adaptive assistance, that level is
 * dismissed for the step, so the step's help is USUAL until the level changes; otherwise nothing
 * changes. The Teaching Mode stays.
 */
private fun usualHelp(state: SessionState): Transition {
    val level = state.assistance
    if (level == AssistanceLevel.USUAL) return Transition(state)
    return Transition(state.copy(assistanceDismissed = state.assistanceDismissed + (state.stepIndex to level)))
}

/** A different mode clears the last Move's feedback and Diagnosis (they belonged to the old mode's attempt); the same mode changes nothing. */
private fun modeChanged(
    state: SessionState,
    event: SessionEvent.TeachingModeChanged,
): Transition =
    Transition(
        if (event.mode == state.teachingMode) {
            state
        } else {
            state.copy(teachingMode = event.mode, lastMoveFeedback = null, lastDiagnosis = null, nextEffectId = state.nextEffectId + 1)
        },
        effects =
            if (event.mode == state.teachingMode) {
                emptyList()
            } else {
                listOf(
                    Effect.UpdatePreference(
                        state.nextEffectId,
                        state.revision,
                        Preference
                            .LastTeachingMode(event.mode),
                    ),
                )
            },
    )

/**
 * [state] with [history] as its new history: the next revision, the Completion Predicate recomputed
 * on the new state, and any pending confirmation and Guided recovery cancelled (they depended on the
 * old state, AD-3).
 */
private fun withHistory(
    state: SessionState,
    history: MoveHistory,
    lesson: Lesson? = state.lesson,
): SessionState {
    val completion = lesson?.completion
    return state.copy(
        revision = state.revision + 1,
        history = history,
        objectiveMet = completion?.isSatisfied(history.state) ?: false,
        progress = completion?.progress(history.state),
        pendingReset = null,
        guidedRecovery = GuidedRecovery.None,
    )
}

private fun restore(
    state: SessionState,
    event: SessionEvent.SessionRestored,
): Transition {
    val snapshot = event.snapshot
    if (state.isActive || !snapshot.kind.resumesOnColdStart ||
        snapshot.kind != SessionKind.LESSON ||
        snapshot.lessonId != event.lesson.id.value
    ) {
        return Transition(state)
    }
    val decoded = FaceletString.decode(snapshot.start) as? FaceletStringResult.Decoded ?: return Transition(state)
    val validated = CubeValidator.validate(decoded.facelets) as? ValidationResult.Valid ?: return Transition(state)
    val done = (Notation.parse(snapshot.done) as? NotationResult.Parsed)?.moves ?: return Transition(state)
    val undone = (Notation.parse(snapshot.undone) as? NotationResult.Parsed)?.moves ?: return Transition(state)
    val solution = (Notation.parse(snapshot.solution) as? NotationResult.Parsed)?.moves ?: return Transition(state)
    val checkpoint =
        snapshot.checkpoint?.let {
            // The Checkpoint may sit on another branch: an Undo below a boundary and a different Move keep it.
            val moves = (Notation.parse(it) as? NotationResult.Parsed)?.moves ?: return Transition(state)
            moves.fold(MoveHistory(validated.state)) { history, move -> history.commit(move) }
        }
    var history = (done + undone.reversed()).fold(MoveHistory(validated.state)) { h, move -> h.commit(move) }
    repeat(undone.size) { history = history.undo() }
    val effectiveHistory = if (snapshot.mode == CubeMode.PHYSICAL) MoveHistory(history.state) else history
    val next =
        withHistory(SessionState.IDLE.copy(revision = state.revision), effectiveHistory, event.lesson).copy(
            kind = snapshot.kind,
            lesson = event.lesson,
            tutorConfig = event.tutorConfig,
            solution = solution,
            checkpoint = checkpoint,
            teachingMode = snapshot.teachingMode,
            adaptiveAssistance = snapshot.adaptiveAssistance,
            hintsUsed = snapshot.hintsUsed,
            attemptOpen = snapshot.attemptOpen,
            attemptAssistance = snapshot.attemptAssistance,
            hintRung = snapshot.hintRung,
            revealRequested = snapshot.revealRequested,
            noticeStep = snapshot.noticeStep,
            stepMistakes = snapshot.stepMistakes,
            cleanSteps = snapshot.cleanSteps,
            cleanCredited = snapshot.cleanCredited,
            stepHelped = snapshot.stepHelped,
            assistanceDismissed = snapshot.assistanceDismissed,
            restoreNote = Text("msg.lesson.resumed"),
            expected = effectiveHistory.state,
            observed = null,
            syncState = if (snapshot.mode == CubeMode.PHYSICAL) SyncState.PHYSICAL_AWAITING_CONFIRM else SyncState.VIRTUAL,
        )
    return Transition(next, presentation = listOf(Presentation.Snap(next.revision, next.virtual)))
}

/** Append after existing effects so IDs and serial writes follow commit order. */
private fun persistCheckpoint(
    before: SessionState,
    transition: Transition,
    event: SessionEvent,
): Transition {
    val next = transition.state
    if (next.kind != SessionKind.LESSON) return transition
    val lesson = next.lesson ?: return transition
    val history = next.checkpoint ?: return transition
    val restored = event is SessionEvent.CheckpointRestored && next !== before
    if (event is SessionEvent.SessionRestored ||
        (history == before.checkpoint && event !is SessionEvent.LessonStarted && !restored)
    ) {
        return transition
    }
    val effect =
        Effect.PersistCheckpoint(
            next.nextEffectId,
            next.revision,
            lesson.id.value,
            lesson.stageId.value,
            history,
            next.teachingMode,
            next.adaptiveAssistance,
            event.at,
            mode = next.mode,
        )
    return transition.copy(state = next.copy(nextEffectId = next.nextEffectId + 1), effects = transition.effects + effect)
}

private fun restoreCheckpoint(
    state: SessionState,
    event: SessionEvent.CheckpointRestored,
): Transition {
    val checkpoint = event.checkpoint
    // A different Lesson is accepted only as the replacement for a removed one: the first Lesson of the Checkpoint's Stage.
    val replaced = checkpoint.lessonId != event.lesson.id.value
    if (state.isActive || checkpoint.kind != SessionKind.LESSON || (replaced && checkpoint.stageId != event.lesson.stageId.value)) {
        return Transition(state)
    }

    fun decode(
        facelets: String,
        orientation: String,
    ): CubeState? {
        val decoded = FaceletString.decode(facelets) as? FaceletStringResult.Decoded ?: return null
        val valid = CubeValidator.validate(decoded.facelets) as? ValidationResult.Valid ?: return null
        return valid.state.takeIf { it.orientation.id() == orientation }
    }
    val start = decode(checkpoint.start, checkpoint.startOrientation) ?: return Transition(state)
    val cube = decode(checkpoint.cube, checkpoint.cubeOrientation) ?: return Transition(state)
    val moves = (Notation.parse(checkpoint.history) as? NotationResult.Parsed)?.moves ?: return Transition(state)
    val stored = moves.fold(MoveHistory(start)) { history, move -> history.commit(move) }
    if (stored.state != cube) return Transition(state)
    val restart = replaced || checkpoint.contentRevision != event.contentRevision || checkpoint.stageId != event.lesson.stageId.value
    val isPhysical = checkpoint.mode == CubeMode.PHYSICAL
    val storedHistory = if (restart) MoveHistory(cube) else stored
    val history = if (isPhysical) MoveHistory(storedHistory.state) else storedHistory
    val fresh = start(state, SessionEvent.LessonStarted(event.lesson, event.tutorConfig, event.at, checkpoint.adaptiveAssistance)).state
    val next =
        withHistory(fresh.copy(revision = state.revision), history).copy(
            checkpoint = history,
            teachingMode = checkpoint.teachingMode,
            restoreNote = if (restart) Text("msg.lesson.updated") else Text("msg.lesson.resumed"),
            expected = history.state,
            observed = null,
            syncState = if (isPhysical) SyncState.PHYSICAL_AWAITING_CONFIRM else SyncState.VIRTUAL,
        )
    return Transition(next, presentation = listOf(Presentation.Snap(next.revision, next.virtual)))
}

/** Fold peak help before closing; attempt bookkeeping never changes the revision. */
private fun attempts(
    before: SessionState,
    transition: Transition,
    event: SessionEvent,
): Transition {
    var next = transition.state
    if (next === before) return transition
    val suspends = event is SessionEvent.SolvePlaybackStarted && (before.kind == SessionKind.LESSON || before.kind == SessionKind.PRACTICE)
    if (next.kind != SessionKind.LESSON && next.kind != SessionKind.PRACTICE && !suspends) return transition
    val starts = event is SessionEvent.LessonStarted || event is SessionEvent.PracticeStarted || event is SessionEvent.CheckpointRestored
    val checkpoint = transition.effects.filterIsInstance<Effect.PersistCheckpoint>()
    val effects = transition.effects.filterNot { it is Effect.PersistCheckpoint }.toMutableList()
    var id = next.nextEffectId - checkpoint.size

    fun end(
        state: SessionState,
        outcome: AttemptOutcome,
    ) {
        effects +=
            Effect.AttemptEnded(
                id++,
                next.revision,
                checkNotNull(state.lesson).id.value,
                outcome,
                state.stepMistakes.values.sum(),
                state.hintsUsed,
                state.attemptAssistance,
                event.at,
            )
    }
    if ((event is SessionEvent.LessonStarted || event is SessionEvent.PracticeStarted || suspends) && before.attemptOpen) {
        end(before, AttemptOutcome.ABANDONED)
    }
    checkpoint.forEach { effects += it.copy(effectId = id++) }
    if (starts) {
        next = next.copy(attemptOpen = !next.objectiveMet, attemptAssistance = next.assistance)
        if (next.attemptOpen) effects += Effect.AttemptStarted(id++, next.revision, checkNotNull(next.lesson).id.value, event.at)
    }
    if (next.attemptOpen) {
        next = next.copy(attemptAssistance = maxOf(next.attemptAssistance, next.assistance))
        if (next.objectiveMet) {
            end(next, AttemptOutcome.COMPLETED)
            next = next.copy(attemptOpen = false)
        }
    }
    val result = transition.copy(state = next.copy(nextEffectId = id), effects = effects)
    return if (result == transition) transition else result
}
