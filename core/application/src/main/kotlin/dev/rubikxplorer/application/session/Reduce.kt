package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.content.FaceletStringResult
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.CubeValidator
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.MoveHistory
import dev.rubikxplorer.domain.cube.ValidationResult
import dev.rubikxplorer.domain.text.Text

/**
 * The pure `LearningSession` reducer (AD-3): no I/O, no wall clock, no randomness, bounded work.
 * `session/TRANSITIONS.md` is the normative table of what each [SessionEvent] mutates.
 */
fun reduce(
    state: SessionState,
    event: SessionEvent,
): Transition {
    if (!state.isActive && event !is SessionEvent.LessonStarted) return Transition(state)
    return when (event) {
        is SessionEvent.LessonStarted -> start(state, event.lesson)
        is SessionEvent.MoveCommitted -> move(state, state.history.commit(event.move), event.move)
        is SessionEvent.Undo -> state.history.undoable?.let { move(state, state.history.undo(), it.inverse()) } ?: Transition(state)
        is SessionEvent.Redo -> state.history.redoable?.let { move(state, state.history.redo(), it) } ?: Transition(state)
        is SessionEvent.Reset -> if (state.hasUnsavedSteps) askReset(state) else reset(state)
        is SessionEvent.ResetConfirmed -> confirmed(state, event)
        is SessionEvent.ResetDeclined -> declined(state, event)
        is SessionEvent.TeachingModeChanged -> Transition(state.copy(teachingMode = event.mode))
        is SessionEvent.RevealRequested -> Transition(state.copy(revealRequested = true))
        is SessionEvent.StepAdvanced -> advance(state)
    }
}

private fun start(
    state: SessionState,
    lesson: Lesson,
): Transition {
    val entry = entryState(lesson)
    val next =
        withHistory(state, MoveHistory(entry), lesson).copy(
            kind = SessionKind.LESSON,
            lesson = lesson,
            lastDiscarded = null,
            stepIndex = 0,
            revealRequested = false,
        )
    return Transition(next, presentation = listOf(Presentation.Snap(next.revision, entry)))
}

/** Loaded content is validated (Story 2.1), so an undecodable entry state is a programmer error. */
private fun entryState(lesson: Lesson): CubeState {
    val decoded = FaceletString.decode(lesson.entryState)
    check(decoded is FaceletStringResult.Decoded) { "Lesson ${lesson.id} has a malformed entry state: $decoded" }
    val validated = CubeValidator.validate(decoded.facelets)
    check(validated is ValidationResult.Valid) { "Lesson ${lesson.id} has an impossible entry state: $validated" }
    return validated.state
}

private fun move(
    state: SessionState,
    history: MoveHistory,
    animate: Move,
): Transition {
    val next = withHistory(state, history)
    return Transition(next, presentation = listOf(Presentation.Animate(animate, next.revision, next.virtual)))
}

/**
 * Back to the entry state and the first step with nothing to undo or redo. Advances
 * [SessionState.revision] only if the history changes.
 */
private fun reset(state: SessionState): Transition {
    val history = state.history.reset()
    val next =
        (if (history == state.history) state.copy(pendingReset = null) else withHistory(state, history))
            .copy(stepIndex = 0, revealRequested = false)
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
 * The next step, when the current one is done but the Lesson is not and a step is left; the Learner
 * then starts it with its Moves hidden again. Otherwise nothing changes.
 */
private fun advance(state: SessionState): Transition {
    val decision = state.decision ?: return Transition(state)
    val lastStep = state.lesson?.steps?.lastIndex ?: return Transition(state)
    val applies = decision.advance && !decision.satisfied && state.stepIndex < lastStep
    return Transition(if (applies) state.copy(stepIndex = state.stepIndex + 1, revealRequested = false) else state)
}

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
