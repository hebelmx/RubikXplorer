package dev.cubentia.domain.tutor

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.LessonId
import dev.cubentia.domain.content.Stage
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.content.TextKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StageProgressTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val stages = bundle.stages
    private val stage1 = stages[0]
    private val stage2 = stages[1]
    private val stage3 = stages[2]
    private val stage4 = stages[3]
    private val stage5 = stages[4]
    private val stage6 = stages[5]
    private val stage7 = stages[6]

    @Test
    fun `fresh epoch unlocks Stage 1 and its first Lesson while locking later Stages and Lessons`() {
        assertEquals(0L, StageProgress.currentEpoch(emptyList()))
        assertEquals(emptyList<ProgressEvent>(), StageProgress.currentEpochEvents(emptyList()))
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
            StageProgress.statuses(stages, emptyList()),
        )
        assertTrue(StageProgress.prerequisitesMet(stage1, stages, emptyList()))
        assertFalse(StageProgress.prerequisitesMet(stage2, stages, emptyList()))
        assertFalse(StageProgress.prerequisitesMet(stage3, stages, emptyList()))

        assertTrue(StageProgress.isLessonUnlocked(stage1.lessons[0], stages, emptyList()))
        assertFalse(StageProgress.isLessonUnlocked(stage1.lessons[1], stages, emptyList()))
        assertFalse(StageProgress.isLessonUnlocked(stage1.lessons[2], stages, emptyList()))
        assertTrue(stage2.lessons.none { StageProgress.isLessonUnlocked(it, stages, emptyList()) })
    }

    @Test
    fun `completing Stage 1 Lessons in order unlocks each next Lesson and then Stage 2`() {
        val afterFirst = listOf(ProgressEvent.LessonCompleted(LessonId("lesson-01-01")))
        assertTrue(StageProgress.isLessonUnlocked(stage1.lessons[1], stages, afterFirst))
        assertFalse(StageProgress.isLessonUnlocked(stage1.lessons[2], stages, afterFirst))
        assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(stage1, stages, afterFirst))
        assertEquals(StageStatus.LOCKED, StageProgress.status(stage2, stages, afterFirst))

        val afterSecond = afterFirst + ProgressEvent.LessonCompleted(LessonId("lesson-01-02"))
        assertTrue(StageProgress.isLessonUnlocked(stage1.lessons[2], stages, afterSecond))
        assertEquals(StageStatus.LOCKED, StageProgress.status(stage2, stages, afterSecond))

        val afterThird = afterSecond + ProgressEvent.LessonCompleted(LessonId("lesson-01-03"))
        assertEquals(listOf(stage2.id), StageProgress.newlyUnlocked(stages, afterSecond, afterThird))
        assertEquals(StageStatus.COMPLETED, StageProgress.status(stage1, stages, afterThird))
        assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(stage2, stages, afterThird))
        assertEquals(StageStatus.LOCKED, StageProgress.status(stage3, stages, afterThird))
        assertTrue(StageProgress.isLessonUnlocked(stage2.lessons[0], stages, afterThird))
        assertFalse(StageProgress.isLessonUnlocked(stage2.lessons[1], stages, afterThird))
    }

    @Test
    fun `skipping a Stage by check or placement marks it skipped, never mastered, and unlocks revisit and next Stage`() {
        val skippedCheck =
            listOf(
                ProgressEvent.StageSkippedByCheck(stage2.id),
                ProgressEvent.StageUnlocked(stage3.id),
            )
        assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(stage1, stages, skippedCheck))
        assertTrue(StageProgress.isLessonUnlocked(stage1.lessons[0], stages, skippedCheck))
        assertFalse(StageProgress.isLessonUnlocked(stage1.lessons[1], stages, skippedCheck))
        assertFalse(StageProgress.isLessonUnlocked(stage1.lessons[2], stages, skippedCheck))
        assertEquals(StageStatus.SKIPPED, StageProgress.status(stage2, stages, skippedCheck))
        assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(stage3, stages, skippedCheck))
        assertTrue(stage2.lessons.all { StageProgress.isLessonUnlocked(it, stages, skippedCheck) })
        assertTrue(StageProgress.isLessonUnlocked(stage3.lessons[0], stages, skippedCheck))
        assertFalse(StageProgress.isLessonUnlocked(stage3.lessons[1], stages, skippedCheck))

        val placement =
            listOf(
                ProgressEvent.StageSkippedByPlacement(stage1.id),
                ProgressEvent.StageSkippedByPlacement(stage2.id),
                ProgressEvent.StageUnlocked(stage3.id),
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
            StageProgress.statuses(stages, placement),
        )
        assertTrue(stage1.lessons.all { StageProgress.isLessonUnlocked(it, stages, placement) })
        assertTrue(stage2.lessons.all { StageProgress.isLessonUnlocked(it, stages, placement) })
    }

    @Test
    fun `revisiting a skipped Stage and completing all its Lessons transitions it to COMPLETED, while mastered overrides`() {
        val skippedThenCompleted =
            listOf(ProgressEvent.StageSkippedByPlacement(stage1.id)) +
                stage1.lessons.map { ProgressEvent.LessonCompleted(it.id) }
        assertEquals(StageStatus.COMPLETED, StageProgress.status(stage1, stages, skippedThenCompleted))
        assertEquals(
            StageStatus.MASTERED,
            StageProgress.status(stage1, stages, skippedThenCompleted, mastered = setOf(stage1.id)),
        )
    }

    @Test
    fun `ProgressReset isolates epochs across multiple resets without deleting prior events`() {
        val events =
            stage1.lessons.map { ProgressEvent.LessonCompleted(it.id) } +
                listOf(
                    ProgressEvent.StageSkippedByCheck(stage2.id),
                    ProgressEvent.ProgressReset(epoch = 1L),
                    ProgressEvent.StageSkippedByPlacement(stage1.id),
                    ProgressEvent.ProgressReset(epoch = 2L),
                )
        assertEquals(2L, StageProgress.currentEpoch(events))
        assertEquals(emptyList<ProgressEvent>(), StageProgress.currentEpochEvents(events))
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
            StageProgress.statuses(stages, events),
        )
    }

    @Test
    fun `empty-lesson Stage uses preceding Stage prerequisite and stays IN_PROGRESS until skipped or mastered`() {
        val practiceStage =
            Stage(
                id = StageId("stage-07-independent-practice"),
                title = TextKey("stage.stage-07-independent-practice.title"),
                lessons = emptyList(),
            )
        val extraPracticeStage =
            Stage(
                id = StageId("stage-08-extra-practice"),
                title = TextKey("stage.stage-08-extra-practice.title"),
                lessons = emptyList(),
            )
        val withPractice = listOf(stage1, practiceStage, extraPracticeStage)

        val unknownStage =
            Stage(
                id = StageId("stage-99-unknown"),
                title = TextKey("stage.stage-99-unknown.title"),
                lessons = emptyList(),
            )
        assertFalse(StageProgress.prerequisitesMet(unknownStage, stages, emptyList()))
        assertEquals(StageStatus.LOCKED, StageProgress.status(unknownStage, stages, emptyList()))

        assertEquals(StageStatus.LOCKED, StageProgress.status(practiceStage, withPractice, emptyList()))
        val stage1Done = stage1.lessons.map { ProgressEvent.LessonCompleted(it.id) }
        assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(practiceStage, withPractice, stage1Done))
        assertEquals(StageStatus.LOCKED, StageProgress.status(extraPracticeStage, withPractice, stage1Done))

        val practiceSkipped = stage1Done + ProgressEvent.StageSkippedByCheck(practiceStage.id)
        assertEquals(StageStatus.SKIPPED, StageProgress.status(practiceStage, withPractice, practiceSkipped))
        assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(extraPracticeStage, withPractice, practiceSkipped))
        assertEquals(
            StageStatus.MASTERED,
            StageProgress.status(practiceStage, withPractice, stage1Done, mastered = setOf(practiceStage.id)),
        )
    }
}
