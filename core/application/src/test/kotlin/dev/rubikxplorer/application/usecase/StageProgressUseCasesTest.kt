package dev.rubikxplorer.application.usecase

import dev.rubikxplorer.application.port.ProgressLoad
import dev.rubikxplorer.application.port.ProgressRecord
import dev.rubikxplorer.application.port.ProgressRepository
import dev.rubikxplorer.application.port.ProgressSave
import dev.rubikxplorer.application.port.ProgressWrite
import dev.rubikxplorer.application.session.entryState
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.StageId
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.tutor.ProgressEvent
import dev.rubikxplorer.domain.tutor.StageProgress
import dev.rubikxplorer.domain.tutor.StageStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class StageProgressUseCasesTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val stages = bundle.stages
    private val stage1 = stages[0]
    private val stage2 = stages[1]
    private val stage3 = stages[2]
    private val stage4 = stages[3]
    private val stage5 = stages[4]
    private val stage6 = stages[5]
    private val stage7 = stages[6]
    private val t0 = Instant.fromEpochMilliseconds(1_000)

    private fun solvedStateFor(lesson: Lesson): CubeState {
        val start = entryState(lesson)
        val moves = (Notation.parse(lesson.demonstration) as NotationResult.Parsed).moves
        return start.apply(moves)
    }

    @Test
    fun `CompleteLesson rejects unsatisfied state even after 10 Moves and appends nothing`() =
        runBlocking {
            val repo = InMemoryProgressRepository()
            val completeLesson = CompleteLesson(repo)
            val lesson = stage1.lessons.first()
            var state = entryState(lesson)
            val rTurn = FaceTurn(Face.R, Turn.CLOCKWISE)
            repeat(10) { state = state.apply(rTurn) }
            assertEquals(false, lesson.completion.isSatisfied(state))

            val result = completeLesson(lesson, state, stages, t0)
            assertEquals(ProgressWrite.PredicateNotMet, result)
            assertEquals(emptyList<ProgressRecord>(), repo.storedRecords)
            assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(stage1, stages, repo.storedEvents))
        }

    @Test
    fun `CompleteLesson appends LessonCompleted and unlocks Stage 2 when Stage 1 finishes`() =
        runBlocking {
            val repo = InMemoryProgressRepository()
            val completeLesson = CompleteLesson(repo)

            for (lesson in stage1.lessons.dropLast(1)) {
                val write = completeLesson(lesson, solvedStateFor(lesson), stages, t0)
                assertEquals(ProgressWrite.Written(listOf(ProgressEvent.LessonCompleted(lesson.id, t0))), write)
                assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(stage1, stages, repo.storedEvents))
                assertEquals(StageStatus.LOCKED, StageProgress.status(stage2, stages, repo.storedEvents))
            }

            val lastLesson = stage1.lessons.last()
            val finalWrite = completeLesson(lastLesson, solvedStateFor(lastLesson), stages, t0)
            assertEquals(
                ProgressWrite.Written(
                    listOf(
                        ProgressEvent.LessonCompleted(lastLesson.id, t0),
                        ProgressEvent.StageUnlocked(stage2.id, t0),
                    ),
                ),
                finalWrite,
            )
            assertEquals(StageStatus.COMPLETED, StageProgress.status(stage1, stages, repo.storedEvents))
            assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(stage2, stages, repo.storedEvents))
            assertEquals(StageStatus.LOCKED, StageProgress.status(stage3, stages, repo.storedEvents))
        }

    @Test
    fun `PassSkipCheck marks Stage SKIPPED never MASTERED and unlocks the following Stage`() =
        runBlocking {
            val repo = InMemoryProgressRepository()
            val passSkipCheck = PassSkipCheck(repo)

            val unknown = StageId("stage-99-unknown")
            assertEquals(ProgressWrite.UnknownStage(unknown), passSkipCheck(unknown, stages, t0))
            assertEquals(ProgressWrite.Written(emptyList()), passSkipCheck(emptyList(), stages, t0))

            val write = passSkipCheck(stage2.id, stages, t0)
            assertEquals(
                ProgressWrite.Written(
                    listOf(
                        ProgressEvent.StageSkippedByCheck(stage2.id, t0),
                        ProgressEvent.StageUnlocked(stage3.id, t0),
                    ),
                ),
                write,
            )
            assertEquals(StageStatus.SKIPPED, StageProgress.status(stage2, stages, repo.storedEvents))
            assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(stage3, stages, repo.storedEvents))
        }

    @Test
    fun `PlaceFromScan skips earlier locked and in-progress Stages and unlocks target Stage`() =
        runBlocking {
            val repo = InMemoryProgressRepository()
            val placeFromScan = PlaceFromScan(repo)

            val unknown = StageId("stage-99-unknown")
            assertEquals(ProgressWrite.UnknownStage(unknown), placeFromScan(unknown, stages, t0))
            assertEquals(ProgressWrite.Written(emptyList()), placeFromScan(stage1.id, stages, t0))

            val write = placeFromScan(stage3.id, stages, t0)
            assertEquals(
                ProgressWrite.Written(
                    listOf(
                        ProgressEvent.StageSkippedByPlacement(stage1.id, t0),
                        ProgressEvent.StageSkippedByPlacement(stage2.id, t0),
                        ProgressEvent.StageUnlocked(stage3.id, t0),
                    ),
                ),
                write,
            )
            assertEquals(
                mapOf(
                    stage1.id to StageStatus.SKIPPED_BY_PLACEMENT,
                    stage2.id to StageStatus.SKIPPED_BY_PLACEMENT,
                    stage3.id to StageStatus.IN_PROGRESS,
                    stage4.id to StageStatus.LOCKED,
                    stage5.id to StageStatus.LOCKED,
                    stage6.id to StageStatus.LOCKED,
                    stage7.id to StageStatus.LOCKED,
                ),
                StageProgress.statuses(stages, repo.storedEvents),
            )
        }

    @Test
    fun `revisiting a placement-skipped Stage and completing its Lessons transitions it to COMPLETED`() =
        runBlocking {
            val repo = InMemoryProgressRepository()
            val placeFromScan = PlaceFromScan(repo)
            val completeLesson = CompleteLesson(repo)

            placeFromScan(stage2.id, stages, t0)
            assertEquals(StageStatus.SKIPPED_BY_PLACEMENT, StageProgress.status(stage1, stages, repo.storedEvents))
            assertTrue(stage1.lessons.all { StageProgress.isLessonUnlocked(it, stages, repo.storedEvents) })

            for (lesson in stage1.lessons) {
                completeLesson(lesson, solvedStateFor(lesson), stages, t0)
            }
            assertEquals(StageStatus.COMPLETED, StageProgress.status(stage1, stages, repo.storedEvents))
        }

    @Test
    fun `ResetProgress appends ProgressReset with incremented epoch and preserves prior rows`() =
        runBlocking {
            val repo = InMemoryProgressRepository()
            val completeLesson = CompleteLesson(repo)
            val passSkipCheck = PassSkipCheck(repo)
            val resetProgress = ResetProgress(repo)

            for (lesson in stage1.lessons) {
                completeLesson(lesson, solvedStateFor(lesson), stages, t0)
            }
            passSkipCheck(stage2.id, stages, t0)
            val countBeforeReset = repo.storedRecords.size
            assertTrue(countBeforeReset > 0)

            val reset1 = resetProgress(t0)
            assertEquals(ProgressWrite.Written(listOf(ProgressEvent.ProgressReset(1L, t0))), reset1)
            assertEquals(countBeforeReset + 1, repo.storedRecords.size)
            assertEquals(
                mapOf(
                    stage1.id to StageStatus.IN_PROGRESS,
                    stage2.id to StageStatus.LOCKED,
                    stage3.id to StageStatus.LOCKED,
                    stage4.id to StageStatus.LOCKED,
                    stage5.id to StageStatus.LOCKED,
                    stage6.id to StageStatus.LOCKED,
                    stage7.id to StageStatus.LOCKED,
                ),
                StageProgress.statuses(stages, repo.storedEvents),
            )

            val reset2 = resetProgress(t0)
            assertEquals(ProgressWrite.Written(listOf(ProgressEvent.ProgressReset(2L, t0))), reset2)
            assertEquals(2L, StageProgress.currentEpoch(repo.storedEvents))
        }

    @Test
    fun `repository read and write failures return ProgressWrite Failed`() =
        runBlocking {
            val repo = InMemoryProgressRepository()
            val completeLesson = CompleteLesson(repo)
            val passSkipCheck = PassSkipCheck(repo)
            val placeFromScan = PlaceFromScan(repo)
            val resetProgress = ResetProgress(repo)
            val lesson = stage1.lessons.first()
            val solved = solvedStateFor(lesson)

            repo.failLoad = true
            assertEquals(ProgressWrite.Failed, completeLesson(lesson, solved, stages, t0))
            assertEquals(ProgressWrite.Failed, passSkipCheck(stage1.id, stages, t0))
            assertEquals(ProgressWrite.Failed, placeFromScan(stage2.id, stages, t0))
            assertEquals(ProgressWrite.Failed, resetProgress(t0))

            repo.failLoad = false
            repo.failAppend = true
            assertEquals(ProgressWrite.Failed, completeLesson(lesson, solved, stages, t0))
            assertEquals(ProgressWrite.Failed, passSkipCheck(stage1.id, stages, t0))
            assertEquals(ProgressWrite.Failed, placeFromScan(stage2.id, stages, t0))
            assertEquals(ProgressWrite.Failed, resetProgress(t0))
        }

    private class InMemoryProgressRepository : ProgressRepository {
        val storedRecords = mutableListOf<ProgressRecord>()
        val storedEvents: List<ProgressEvent> get() = storedRecords.map { it.event }
        var failLoad: Boolean = false
        var failAppend: Boolean = false

        override suspend fun all(): ProgressLoad =
            if (failLoad) {
                ProgressLoad.Failed
            } else {
                ProgressLoad.Found(storedEvents, storedRecords.toList())
            }

        override suspend fun append(records: List<ProgressRecord>): ProgressSave {
            if (failAppend) return ProgressSave.Failed
            for (record in records) {
                val duplicate =
                    storedRecords.any { existing ->
                        existing.sessionId == record.sessionId &&
                            existing.revision == record.revision &&
                            existing.event::class == record.event::class &&
                            (existing.event as? ProgressEvent.LessonCompleted)?.lessonId ==
                            (record.event as? ProgressEvent.LessonCompleted)?.lessonId &&
                            stageIdOf(existing.event) == stageIdOf(record.event)
                    }
                if (!duplicate) storedRecords += record
            }
            return ProgressSave.Saved
        }

        private fun stageIdOf(event: ProgressEvent): StageId? =
            when (event) {
                is ProgressEvent.StageSkippedByCheck -> event.stageId
                is ProgressEvent.StageSkippedByPlacement -> event.stageId
                is ProgressEvent.StageUnlocked -> event.stageId
                is ProgressEvent.LessonCompleted, is ProgressEvent.ProgressReset -> null
            }
    }
}
