package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.content.Stage
import dev.rubikxplorer.domain.content.StageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class StageTrendTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val stages: List<Stage> = bundle.stages
    private val stage1: Stage = stages.first { it.id == StageId("stage-01-fundamentals") }
    private val stage2: Stage = stages.first { it.id == StageId("stage-02-first-cross") }
    private val stage3: Stage = stages.first { it.id == StageId("stage-03-first-layer-corners") }

    @Test
    fun `zero attempts for stage returns empty trend`() {
        val trend = StageTrendCalculator.evaluate(stage2, emptyList())
        assertEquals(stage2.id, trend.stageId)
        assertEquals(0, trend.attemptsCount)
        assertEquals(emptyList<Int>(), trend.hintCounts)
    }

    @Test
    fun `single attempt returns one dot with hints and attempts count 1`() {
        val attempts =
            listOf(
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    outcome = AttemptOutcome.COMPLETED,
                    at = Instant.fromEpochMilliseconds(100),
                    hints = 3,
                ),
            )
        val trend = StageTrendCalculator.evaluate(stage2, attempts)
        assertEquals(stage2.id, trend.stageId)
        assertEquals(1, trend.attemptsCount)
        assertEquals(listOf(3), trend.hintCounts)
    }

    @Test
    fun `multiple attempts in out of order timestamps are sorted chronologically`() {
        val attempts =
            listOf(
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    at = Instant.fromEpochMilliseconds(500),
                    hints = 1,
                ),
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    at = Instant.fromEpochMilliseconds(100),
                    hints = 3,
                ),
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    at = Instant.fromEpochMilliseconds(400),
                    hints = 1,
                ),
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    at = Instant.fromEpochMilliseconds(200),
                    hints = 2,
                ),
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    at = Instant.fromEpochMilliseconds(300),
                    hints = 2,
                ),
            )
        val trend = StageTrendCalculator.evaluate(stage2, attempts)
        assertEquals(5, trend.attemptsCount)
        assertEquals(listOf(3, 2, 2, 1, 1), trend.hintCounts)
    }

    @Test
    fun `zero hints on attempts preserves 0 hint counts`() {
        val attempts =
            listOf(
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    at = Instant.fromEpochMilliseconds(100),
                    hints = 0,
                ),
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    at = Instant.fromEpochMilliseconds(200),
                    hints = 0,
                ),
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    at = Instant.fromEpochMilliseconds(300),
                    hints = 0,
                ),
            )
        val trend = StageTrendCalculator.evaluate(stage2, attempts)
        assertEquals(3, trend.attemptsCount)
        assertEquals(listOf(0, 0, 0), trend.hintCounts)
    }

    @Test
    fun `null hints on attempt defaults to 0`() {
        val attempts =
            listOf(
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    at = Instant.fromEpochMilliseconds(100),
                    hints = null,
                ),
            )
        val trend = StageTrendCalculator.evaluate(stage2, attempts)
        assertEquals(1, trend.attemptsCount)
        assertEquals(listOf(0), trend.hintCounts)
    }

    @Test
    fun `epoch isolation after reset filters out prior epoch attempts`() {
        val resetAt = Instant.fromEpochMilliseconds(1000)
        val attempts =
            listOf(
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    at = Instant.fromEpochMilliseconds(100),
                    hints = 4,
                ),
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    at = Instant.fromEpochMilliseconds(200),
                    hints = 3,
                ),
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    at = Instant.fromEpochMilliseconds(1500),
                    hints = 1,
                ),
            )
        val events =
            listOf(
                ProgressEvent.ProgressReset(epoch = 1L, at = resetAt),
            )

        val trend = StageTrendCalculator.evaluate(stage2, attempts, events)
        assertEquals(1, trend.attemptsCount)
        assertEquals(listOf(1), trend.hintCounts)
    }

    @Test
    fun `matchesStage matches practice, stageId, and all stage lessons`() {
        val practiceAttempt =
            StageAttempt(
                objectiveId = "practice-${stage1.id.value}",
                at = Instant.fromEpochMilliseconds(100),
            )
        val stageIdAttempt =
            StageAttempt(
                objectiveId = stage1.id.value,
                at = Instant.fromEpochMilliseconds(200),
            )
        val lesson1Attempt =
            StageAttempt(
                objectiveId =
                    stage1.lessons
                        .first()
                        .id.value,
                at = Instant.fromEpochMilliseconds(300),
            )
        val lesson2Attempt =
            StageAttempt(
                objectiveId = stage1.lessons[1].id.value,
                at = Instant.fromEpochMilliseconds(400),
            )

        assertTrue(StageTrendCalculator.matchesStage(practiceAttempt, stage1))
        assertTrue(StageTrendCalculator.matchesStage(stageIdAttempt, stage1))
        assertTrue(StageTrendCalculator.matchesStage(lesson1Attempt, stage1))
        assertTrue(StageTrendCalculator.matchesStage(lesson2Attempt, stage1))
    }

    @Test
    fun `matchesStage ignores other stages`() {
        val stage3Attempt =
            StageAttempt(
                objectiveId = "practice-${stage3.id.value}",
                at = Instant.fromEpochMilliseconds(100),
            )
        val stage3LessonAttempt =
            StageAttempt(
                objectiveId =
                    stage3.lessons
                        .first()
                        .id.value,
                at = Instant.fromEpochMilliseconds(200),
            )

        assertFalse(StageTrendCalculator.matchesStage(stage3Attempt, stage2))
        assertFalse(StageTrendCalculator.matchesStage(stage3LessonAttempt, stage2))
    }

    @Test
    fun `ignores attempts that have not ended`() {
        val startedOnly =
            StageAttempt(
                objectiveId = "practice-${stage2.id.value}",
                outcome = null,
                at = Instant.fromEpochMilliseconds(100),
                hints = 2,
            )
        val ended =
            StageAttempt(
                objectiveId = "practice-${stage2.id.value}",
                outcome = AttemptOutcome.COMPLETED,
                at = Instant.fromEpochMilliseconds(200),
                hints = 1,
            )
        val abandoned =
            StageAttempt(
                objectiveId = "practice-${stage2.id.value}",
                outcome = AttemptOutcome.ABANDONED,
                at = Instant.fromEpochMilliseconds(300),
                hints = 3,
            )

        val trend = StageTrendCalculator.evaluate(stage2, listOf(startedOnly, ended, abandoned))
        assertEquals(2, trend.attemptsCount)
        assertEquals(listOf(1, 3), trend.hintCounts)
    }
}
