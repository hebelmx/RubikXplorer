package dev.rubikxplorer.app.session

import dev.rubikxplorer.application.port.Clock
import dev.rubikxplorer.application.port.FeatureFlag
import dev.rubikxplorer.application.port.FeatureFlags
import dev.rubikxplorer.application.port.FlagLevel
import dev.rubikxplorer.domain.content.ContentBundle
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.LessonId
import dev.rubikxplorer.domain.content.RecoveryRef
import dev.rubikxplorer.domain.content.Stage
import dev.rubikxplorer.domain.content.StageId
import dev.rubikxplorer.domain.content.Step
import dev.rubikxplorer.domain.content.TextKey
import dev.rubikxplorer.domain.content.TutorConfig
import dev.rubikxplorer.domain.cube.Axis
import dev.rubikxplorer.domain.cube.CubeRotation
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Slice
import dev.rubikxplorer.domain.cube.SliceTurn
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.cube.WideTurn
import dev.rubikxplorer.domain.predicate.AlwaysTrue
import dev.rubikxplorer.domain.predicate.AnyOf
import dev.rubikxplorer.domain.predicate.StateReached
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Instant

/** The white-cross Lesson of the domain `stage-01-basics` fixture: entry SOLVED + F, solved by F'. */
internal fun whiteCrossLesson(id: String = "lesson-01-01"): Lesson =
    Lesson(
        id = LessonId(id),
        stageId = StageId("stage-01-basics"),
        prerequisites = emptyList(),
        objective = TextKey("lesson.$id.objective"),
        entryState = FaceletString.encode(CubeState.SOLVED.apply(FaceTurn(Face.F, Turn.CLOCKWISE)).toFacelets()),
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
    return base.copy(
        entryState =
            FaceletString.encode(
                CubeState.SOLVED.apply(FaceTurn(Face.F, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.COUNTERCLOCKWISE)).toFacelets(),
            ),
        demonstration = "U F'",
        steps =
            (1..2).map { n ->
                Step(
                    goal = TextKey("lesson.$id.goal.$n"),
                    preserve = TextKey("lesson.$id.keep.$n"),
                    watch = TextKey("lesson.$id.watch.$n"),
                    notice = TextKey("lesson.$id.notice.$n"),
                    moves = if (n == 1) "U" else "F'",
                    done = if (n == 1) AnyOf(listOf(StateReached("F"), WhiteCrossSolved)) else WhiteCrossSolved,
                )
            },
    )
}

/** The shipped `tutor-config.json`, loaded once. */
internal val shippedTutorConfig: TutorConfig by lazy {
    (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.tutorConfig
}

/** A one-step white-cross Lesson: entry SOLVED + F + U' (no cross edge home), its one step `U F'`. */
internal fun oneLongStepLesson(id: String = "lesson-01-04"): Lesson {
    val base = whiteCrossLesson(id)
    return base.copy(
        entryState =
            FaceletString.encode(
                CubeState.SOLVED.apply(FaceTurn(Face.F, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.COUNTERCLOCKWISE)).toFacelets(),
            ),
        demonstration = "U F'",
        steps = listOf(base.steps.single().copy(moves = "U F'")),
    )
}

/** The English strings of [lesson]: each text field resolves to `<field> <n> of <lesson id>`. */
internal fun stringsOf(lesson: Lesson): Map<String, String> {
    val id = lesson.id.value
    return buildMap {
        put(lesson.objective.value, "Objective of $id")
        put(lesson.learnerAction.value, "Action of $id")
        lesson.steps.forEachIndexed { i, step ->
            val n = i + 1
            put(step.goal.value, "Goal $n of $id")
            put(step.preserve.value, "Keep $n of $id")
            put(step.watch.value, "Watch $n of $id")
            put(step.notice.value, "Notice $n of $id")
        }
    }
}

/** The stage the test bundles hold their Lessons in, titled by [STAGE_TITLE]. */
internal const val STAGE_TITLE = "Basics"

/** A bundle of one Stage holding [lessons], with their strings, the Stage title and the shipped `msg.*` strings. */
internal fun testBundle(vararg lessons: Lesson = arrayOf(whiteCrossLesson(), twoStepLesson())): ContentBundle {
    val shipped = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    val stage = Stage(StageId("stage-01-basics"), TextKey("stage.stage-01-basics.title"), lessons.toList())
    val strings =
        shipped.strings + mapOf(stage.title.value to STAGE_TITLE) + lessons.flatMap { stringsOf(it).entries.map { e -> e.toPair() } }
    return ContentBundle(shipped.schemaVersion, "test", listOf(stage), strings, shipped.tutorConfig)
}

/** Every Move value of every type: 18 face, 18 wide, 9 slice, 9 rotation. */
internal val allMoves: List<Move> =
    Turn.entries.flatMap { t ->
        Face.entries.map { FaceTurn(it, t) } +
            Face.entries.map { WideTurn(it, t) } +
            Slice.entries.map { SliceTurn(it, t) } +
            Axis.entries.map { CubeRotation(it, t) }
    }

/** A thread-safe clock that ticks one millisecond per read, from [startMillis]. */
internal class TickingClock(
    startMillis: Long = 1_000,
) : Clock {
    private val millis = AtomicLong(startMillis)

    override fun now(): Instant = Instant.fromEpochMilliseconds(millis.getAndIncrement())
}

/** Feature flags with every flag at GA, except `TEST_ME` OFF unless [testMe]. */
internal fun testFlags(testMe: Boolean = true): FeatureFlags =
    object : FeatureFlags {
        override fun level(flag: FeatureFlag): FlagLevel = if (flag == FeatureFlag.TEST_ME && !testMe) FlagLevel.OFF else FlagLevel.GA
    }
