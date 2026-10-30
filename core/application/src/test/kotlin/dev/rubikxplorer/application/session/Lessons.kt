package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.LessonId
import dev.rubikxplorer.domain.content.RecoveryRef
import dev.rubikxplorer.domain.content.StageId
import dev.rubikxplorer.domain.content.Step
import dev.rubikxplorer.domain.content.TextKey
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved
import kotlin.time.Instant

internal val F = FaceTurn(Face.F, Turn.CLOCKWISE)
internal val F_PRIME = FaceTurn(Face.F, Turn.COUNTERCLOCKWISE)
internal val R = FaceTurn(Face.R, Turn.CLOCKWISE)
internal val U = FaceTurn(Face.U, Turn.CLOCKWISE)

internal val T0: Instant = Instant.fromEpochMilliseconds(1_000)

/** The white-cross Lesson of the `stage-01-basics` fixture: entry SOLVED + F, solved by F'. */
internal fun whiteCrossLesson(id: String = "lesson-01-01"): Lesson =
    Lesson(
        id = LessonId(id),
        stageId = StageId("stage-01-basics"),
        prerequisites = emptyList(),
        objective = TextKey("lesson.$id.objective"),
        entryState = FaceletString.encode(CubeState.SOLVED.apply(F).toFacelets()),
        demonstration = "F'",
        learnerAction = TextKey("lesson.$id.learner-action"),
        completion = WhiteCrossSolved,
        preserve = WhiteCrossSolved,
        recovery = RecoveryRef("recovery-undo-last-move"),
        steps =
            listOf(
                Step(
                    goal = TextKey("lesson.$id.goal.1"),
                    preserve = TextKey("lesson.$id.keep.1"),
                    watch = TextKey("lesson.$id.watch.1"),
                    notice = TextKey("lesson.$id.notice.1"),
                    moves = "F'",
                ),
            ),
    )

/** Runs [events] from [start], returning every transition. */
internal fun run(
    start: SessionState,
    vararg events: SessionEvent,
): List<Transition> {
    var state = start
    return events.map { event -> reduce(state, event).also { state = it.state } }
}

internal fun SessionState.after(vararg events: SessionEvent): SessionState = run(this, *events).lastOrNull()?.state ?: this

internal fun started(lesson: Lesson = whiteCrossLesson()): SessionState = SessionState.IDLE.after(SessionEvent.LessonStarted(lesson, T0))

internal fun commit(move: dev.rubikxplorer.domain.cube.Move) = SessionEvent.MoveCommitted(move, T0)

internal val undo = SessionEvent.Undo(T0)
internal val redo = SessionEvent.Redo(T0)
internal val resetEvent = SessionEvent.Reset(T0)

internal fun Effect.ConfirmReset.confirm() = SessionEvent.ResetConfirmed(effectId, revision, T0)

internal fun Effect.ConfirmReset.decline() = SessionEvent.ResetDeclined(effectId, revision, T0)
