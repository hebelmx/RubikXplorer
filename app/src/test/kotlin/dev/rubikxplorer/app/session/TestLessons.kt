package dev.rubikxplorer.app.session

import dev.rubikxplorer.application.port.Clock
import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.LessonId
import dev.rubikxplorer.domain.content.RecoveryRef
import dev.rubikxplorer.domain.content.StageId
import dev.rubikxplorer.domain.content.Step
import dev.rubikxplorer.domain.content.TextKey
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
