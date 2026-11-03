package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.content.FaceletStringResult
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.TutorConfig
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.CubeValidator
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.MoveHistory
import dev.rubikxplorer.domain.cube.ValidationResult
import dev.rubikxplorer.domain.text.Text
import dev.rubikxplorer.domain.tutor.MoveFeedback
import dev.rubikxplorer.domain.tutor.Tutor

/**
 * The pure `LearningSession` reducer (AD-3): no I/O, no wall clock, no randomness, bounded work.
 * `session/TRANSITIONS.md` is the normative table of what each [SessionEvent] mutates.
 */
fun reduce(
    state: SessionState,
    event: SessionEvent,
): Transition {
    val starts = event is SessionEvent.LessonStarted || event is SessionEvent.SolvePlaybackStarted
    // Only a Lesson session takes Moves and tutor events; idle and playback sessions ignore them.
    if (!starts && state.kind != SessionKind.LESSON) return Transition(state)
    return when (event) {
        is SessionEvent.LessonStarted -> start(state, event.lesson, event.tutorConfig)
        is SessionEvent.SolvePlaybackStarted -> startPlayback(state, event.state, event.solution)
        is SessionEvent.MoveCommitted -> committed(state, event.move)
        is SessionEvent.Undo -> state.history.undoable?.let { move(state, state.history.undo(), it.inverse(), null) } ?: Transition(state)
        is SessionEvent.Redo -> state.history.redoable?.let { move(state, state.history.redo(), it, null) } ?: Transition(state)
        is SessionEvent.Reset -> if (state.hasUnsavedSteps) askReset(state) else reset(state)
        is SessionEvent.ResetConfirmed -> confirmed(state, event)
        is SessionEvent.ResetDeclined -> declined(state, event)
        is SessionEvent.TeachingModeChanged -> modeChanged(state, event)
        is SessionEvent.RevealRequested -> Transition(state.copy(revealRequested = true))
        is SessionEvent.StepAdvanced -> advance(state)
    }
}

private fun start(
    state: SessionState,
    lesson: Lesson,
    tutorConfig: TutorConfig,
): Transition {
    val entry = entryState(lesson)
    val next =
        withHistory(state, MoveHistory(entry), lesson).copy(
            kind = SessionKind.LESSON,
            lesson = lesson,
            tutorConfig = tutorConfig,
            solution = emptyList(),
            lastDiscarded = null,
            noticeStep = null,
            revealRequested = false,
            lastMoveFeedback = null,
        )
    return Transition(next, presentation = listOf(Presentation.Snap(next.revision, entry)))
}

/**
 * Starts a Solve Playback session at [start] holding [solution], when [solution] verifiably solves
 * [start] (bounded: one pass over the list); otherwise nothing changes. The Teaching Mode is kept.
 * AD-3 suspends a session of another kind before a new one starts; until suspension exists
 * (Story 3.7), an active session of another kind rejects the start and stays unchanged.
 */
private fun startPlayback(
    state: SessionState,
    start: CubeState,
    solution: List<Move>,
): Transition {
    if (state.isActive && state.kind != SessionKind.SOLVE_PLAYBACK) return Transition(state)
    if (!start.apply(solution).isSolved) return Transition(state)
    val next =
        withHistory(state, MoveHistory(start), lesson = null).copy(
            kind = SessionKind.SOLVE_PLAYBACK,
            lesson = null,
            tutorConfig = null,
            solution = solution.toList(),
            lastDiscarded = null,
            noticeStep = null,
            revealRequested = false,
            lastMoveFeedback = null,
        )
    return Transition(next, presentation = listOf(Presentation.Snap(next.revision, start)))
}

/** Loaded content is validated (Story 2.1), so an undecodable entry state is a programmer error. */
private fun entryState(lesson: Lesson): CubeState {
    val decoded = FaceletString.decode(lesson.entryState)
    check(decoded is FaceletStringResult.Decoded) { "Lesson ${lesson.id} has a malformed entry state: $decoded" }
    val validated = CubeValidator.validate(decoded.facelets)
    check(validated is ValidationResult.Valid) { "Lesson ${lesson.id} has an impossible entry state: $validated" }
    return validated.state
}

/** Commits [move] and classifies it against the Completion Predicate, whatever the Teaching Mode. */
private fun committed(
    state: SessionState,
    move: Move,
): Transition {
    val history = state.history.commit(move)
    val feedback = state.lesson?.let { MoveFeedback.classify(it.completion, state.virtual, history.state) }
    return move(state, history, move, feedback)
}

/**
 * [state] moved to [history], animating [animate]; [feedback] becomes the last Move's feedback. With
 * `s0` the step shown before and `d1` the current step after: when `d1 > s0` and the Lesson is not
 * complete, `s0`'s Notice stays until *Next* (no auto-advance past a Notice); otherwise no Notice is
 * pending. The reveal resets whenever the shown step changes.
 */
private fun move(
    state: SessionState,
    history: MoveHistory,
    animate: Move,
    feedback: MoveFeedback?,
): Transition {
    val moved = withHistory(state, history).copy(lastMoveFeedback = feedback)
    val next = moved.lesson?.let { lesson -> withNotice(state, moved, lesson) } ?: moved
    return Transition(next, presentation = listOf(Presentation.Animate(animate, next.revision, next.virtual)))
}

private fun withNotice(
    before: SessionState,
    moved: SessionState,
    lesson: Lesson,
): SessionState {
    val shownBefore = before.stepIndex
    val current = Tutor.currentStep(lesson, moved.entry, moved.virtual)
    // Only a step that is done now earns its Notice: a return onto the path mid-step may not redo earlier steps.
    val notice = shownBefore.takeIf { current > it && !moved.objectiveMet && lesson.steps[it].done.isSatisfied(moved.virtual) }
    val next = moved.copy(noticeStep = notice)
    return if (next.stepIndex == shownBefore) next else next.copy(revealRequested = false)
}

/**
 * Back to the entry state and the first step with nothing to undo or redo. Advances
 * [SessionState.revision] only if the history changes.
 */
private fun reset(state: SessionState): Transition {
    val history = state.history.reset()
    val next =
        (if (history == state.history) state.copy(pendingReset = null) else withHistory(state, history))
            .copy(noticeStep = null, revealRequested = false, lastMoveFeedback = null)
    return Transition(next, presentation = listOf(Presentation.Snap(next.revision, next.entry)))
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
    return if (overtaken && next.hasUnsavedSteps && next.pendingReset == null) askReset(next) else Transition(next)
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
 * Past a completed step's Notice, when the tutor says `advance` and the Lesson is not complete: the
 * Notice clears, so the card shows the step derived from the cube, with its Moves hidden again.
 * Otherwise nothing changes.
 */
private fun advance(state: SessionState): Transition {
    val decision = state.decision ?: return Transition(state)
    val applies = decision.advance && !decision.satisfied
    return Transition(if (applies) state.copy(noticeStep = null, revealRequested = false, lastMoveFeedback = null) else state)
}

/** A different mode clears the last Move's feedback (it belonged to the old mode's attempt); the same mode changes nothing. */
private fun modeChanged(
    state: SessionState,
    event: SessionEvent.TeachingModeChanged,
): Transition = Transition(if (event.mode == state.teachingMode) state else state.copy(teachingMode = event.mode, lastMoveFeedback = null))

/**
 * [state] with [history] as its new history: the next revision, the Completion Predicate recomputed
 * on the new state, and any pending confirmation cancelled (it depended on the old state, AD-3).
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
    )
}
