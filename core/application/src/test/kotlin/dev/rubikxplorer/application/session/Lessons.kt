package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.LessonId
import dev.rubikxplorer.domain.content.RecoveryRef
import dev.rubikxplorer.domain.content.StageId
import dev.rubikxplorer.domain.content.Step
import dev.rubikxplorer.domain.content.TextKey
import dev.rubikxplorer.domain.content.TutorConfig
import dev.rubikxplorer.domain.cube.Axis
import dev.rubikxplorer.domain.cube.CubeRotation
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.predicate.AlwaysTrue
import dev.rubikxplorer.domain.predicate.AnyOf
import dev.rubikxplorer.domain.predicate.StateReached
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved
import dev.rubikxplorer.domain.tutor.Diagnosis
import dev.rubikxplorer.domain.tutor.TeachingMode
import kotlin.time.Instant

internal val F = FaceTurn(Face.F, Turn.CLOCKWISE)
internal val F_PRIME = FaceTurn(Face.F, Turn.COUNTERCLOCKWISE)
internal val R = FaceTurn(Face.R, Turn.CLOCKWISE)
internal val R_PRIME = FaceTurn(Face.R, Turn.COUNTERCLOCKWISE)
internal val U = FaceTurn(Face.U, Turn.CLOCKWISE)
internal val U_PRIME = FaceTurn(Face.U, Turn.COUNTERCLOCKWISE)
internal val D = FaceTurn(Face.D, Turn.CLOCKWISE)
internal val Y = CubeRotation(Axis.Y, Turn.CLOCKWISE)

internal val T0: Instant = Instant.fromEpochMilliseconds(1_000)

private val SHIPPED by lazy {
    val result = ContentLoader.load(ContentSource.SHIPPED)
    check(result is ContentLoadResult.Loaded) { "the shipped content does not load: $result" }
    result.bundle
}

/** The shipped `tutor-config.json`, loaded once. */
internal val TUTOR_CONFIG: TutorConfig by lazy { SHIPPED.tutorConfig }

/** The shipped Lesson [id]. */
internal fun shippedLesson(id: String): Lesson = SHIPPED.stages.flatMap { it.lessons }.single { it.id.value == id }

/** A real Diagnosis (a turned cube in *Test me*), for fixtures that need one standing. */
internal val TURNED_DIAGNOSIS: Diagnosis by lazy {
    checkNotNull(started().after(modeChanged(TeachingMode.TEST_ME), commit(Y)).lastDiagnosis)
}

/** Starts [lesson] with the shipped tutor configuration. */
internal fun lessonStarted(lesson: Lesson) = SessionEvent.LessonStarted(lesson, TUTOR_CONFIG, T0)

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
        preserve = AlwaysTrue, // a first Lesson: nothing earlier to keep (AD-6)
        recovery = RecoveryRef("recovery-undo-last-move"),
        steps =
            listOf(
                Step(
                    goal = TextKey("lesson.$id.goal.1"),
                    preserve = TextKey("lesson.$id.keep.1"),
                    watch = TextKey("lesson.$id.watch.1"),
                    notice = TextKey("lesson.$id.notice.1"),
                    moves = "F'",
                    done = WhiteCrossSolved,
                ),
            ),
    )

/** A two-step white-cross Lesson: entry SOLVED + F + U', step 1 `U` (back to SOLVED + F), step 2 `F'`. */
internal fun twoStepLesson(id: String = "lesson-01-03"): Lesson {
    val base = whiteCrossLesson(id)
    val step = base.steps.single()
    return base.copy(
        entryState = FaceletString.encode(CubeState.SOLVED.apply(F, U_PRIME).toFacelets()),
        demonstration = "U F'",
        steps =
            listOf(
                step.copy(moves = "U", done = AnyOf(listOf(StateReached("F"), WhiteCrossSolved))),
                Step(
                    goal = TextKey("lesson.$id.goal.2"),
                    preserve = TextKey("lesson.$id.keep.2"),
                    watch = TextKey("lesson.$id.watch.2"),
                    notice = TextKey("lesson.$id.notice.2"),
                    moves = "F'",
                    done = WhiteCrossSolved,
                ),
            ),
    )
}

/** A one-step white-cross Lesson: entry SOLVED + F + U' (no cross edge home), its one step `U F'`. */
internal fun oneLongStepLesson(id: String = "lesson-01-04"): Lesson {
    val base = whiteCrossLesson(id)
    return base.copy(
        entryState = FaceletString.encode(CubeState.SOLVED.apply(F, U_PRIME).toFacelets()),
        demonstration = "U F'",
        steps = listOf(base.steps.single().copy(moves = "U F'")),
    )
}

/** Runs [events] from [start], returning every transition. */
internal fun run(
    start: SessionState,
    vararg events: SessionEvent,
): List<Transition> {
    var state = start
    return events.map { event -> reduce(state, event).also { state = it.state } }
}

internal fun SessionState.after(vararg events: SessionEvent): SessionState = run(this, *events).lastOrNull()?.state ?: this

internal fun started(lesson: Lesson = whiteCrossLesson()): SessionState = SessionState.IDLE.after(lessonStarted(lesson))

/** A Solve Playback fixture: SOLVED + R U, solved by its [SOLUTION]. */
internal val SCRAMBLED: CubeState = CubeState.SOLVED.apply(R, U)

/** The verified Solution of [SCRAMBLED]. */
internal val SOLUTION: List<dev.rubikxplorer.domain.cube.Move> = listOf(U_PRIME, R_PRIME)

internal fun playbackStarted(
    state: CubeState = SCRAMBLED,
    solution: List<dev.rubikxplorer.domain.cube.Move> = SOLUTION,
) = SessionEvent.SolvePlaybackStarted(state, solution, T0)

internal fun commit(move: dev.rubikxplorer.domain.cube.Move) = SessionEvent.MoveCommitted(move, T0)

internal val undo = SessionEvent.Undo(T0)
internal val redo = SessionEvent.Redo(T0)
internal val resetEvent = SessionEvent.Reset(T0)
internal val reveal = SessionEvent.RevealRequested(T0)
internal val hint = SessionEvent.HintRequested(T0)
internal val stepAdvanced = SessionEvent.StepAdvanced(T0)
internal val retry = SessionEvent.RetryRequested(T0)

internal fun modeChanged(mode: TeachingMode) = SessionEvent.TeachingModeChanged(mode, T0)

internal fun Effect.ConfirmReset.confirm() = SessionEvent.ResetConfirmed(effectId, revision, T0)

internal fun Effect.ConfirmReset.decline() = SessionEvent.ResetDeclined(effectId, revision, T0)

internal val guideBack = SessionEvent.GuidedRecoveryRequested(T0)

internal fun Effect.PlanRecovery.planned(moves: List<dev.rubikxplorer.domain.cube.Move>) =
    SessionEvent.RecoveryPlanned(effectId, revision, moves, T0)

internal fun Effect.PlanRecovery.failed() = SessionEvent.RecoveryPlanFailed(effectId, revision, T0)
