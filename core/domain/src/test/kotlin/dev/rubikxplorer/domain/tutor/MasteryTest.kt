package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.content.MasteryThresholds
import dev.rubikxplorer.domain.content.Stage
import dev.rubikxplorer.domain.content.StageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class MasteryTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val stages: List<Stage> = bundle.stages
    private val stage2: Stage = stages.first { it.id == StageId("stage-02-first-cross") }
    private val stage3: Stage = stages.first { it.id == StageId("stage-03-first-layer-corners") }
    private val defaultConfig = bundle.tutorConfig.mastery

    @Test
    fun `zero attempts leaves solvesCompleted 0, reducedHelpSolves 0 and not mastered`() {
        val progress = Mastery.evaluate(stage2, emptyList(), defaultConfig)
        assertEquals(stage2.id, progress.stageId)
        assertEquals(0, progress.solvesCompleted)
        assertEquals(0, progress.reducedHelpSolves)
        assertEquals(2, progress.solvesRequired)
        assertEquals(1, progress.reducedHelpRequired)
        assertFalse(progress.isMastered)
    }

    @Test
    fun `one solve with full help does not satisfy mastery condition`() {
        val attempts =
            listOf(
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    outcome = AttemptOutcome.COMPLETED,
                    assistanceLevel = AssistanceLevel.USUAL,
                ),
            )
        val progress = Mastery.evaluate(stage2, attempts, defaultConfig)
        assertEquals(1, progress.solvesCompleted)
        assertEquals(0, progress.reducedHelpSolves)
        assertFalse(progress.isMastered)
    }

    @Test
    fun `two solves with full help does not satisfy mastery condition due to reduced help requirement`() {
        val attempts =
            listOf(
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    outcome = AttemptOutcome.COMPLETED,
                    assistanceLevel = AssistanceLevel.USUAL,
                ),
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    outcome = AttemptOutcome.COMPLETED,
                    assistanceLevel = AssistanceLevel.GUIDE,
                ),
            )
        val progress = Mastery.evaluate(stage2, attempts, defaultConfig)
        assertEquals(2, progress.solvesCompleted)
        assertEquals(0, progress.reducedHelpSolves)
        assertFalse(progress.isMastered)
    }

    @Test
    fun `one solve with reduced help does not satisfy mastery condition due to solves count`() {
        val attempts =
            listOf(
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    outcome = AttemptOutcome.COMPLETED,
                    assistanceLevel = AssistanceLevel.REDUCED,
                ),
            )
        val progress = Mastery.evaluate(stage2, attempts, defaultConfig)
        assertEquals(1, progress.solvesCompleted)
        assertEquals(1, progress.reducedHelpSolves)
        assertFalse(progress.isMastered)
    }

    @Test
    fun `two completed solves with one reduced help meets mastery condition`() {
        val attempts =
            listOf(
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    outcome = AttemptOutcome.COMPLETED,
                    assistanceLevel = AssistanceLevel.USUAL,
                ),
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    outcome = AttemptOutcome.COMPLETED,
                    assistanceLevel = AssistanceLevel.REDUCED,
                ),
            )
        val progress = Mastery.evaluate(stage2, attempts, defaultConfig)
        assertEquals(2, progress.solvesCompleted)
        assertEquals(1, progress.reducedHelpSolves)
        assertTrue(progress.isMastered)
    }

    @Test
    fun `two completed solves with two reduced help meets mastery condition`() {
        val attempts =
            listOf(
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    outcome = AttemptOutcome.COMPLETED,
                    assistanceLevel = AssistanceLevel.REDUCED,
                ),
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    outcome = AttemptOutcome.COMPLETED,
                    assistanceLevel = AssistanceLevel.REDUCED,
                ),
            )
        val progress = Mastery.evaluate(stage2, attempts, defaultConfig)
        assertEquals(2, progress.solvesCompleted)
        assertEquals(2, progress.reducedHelpSolves)
        assertTrue(progress.isMastered)
    }

    @Test
    fun `abandoned attempt does not count toward solves`() {
        val attempts =
            listOf(
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    outcome = AttemptOutcome.ABANDONED,
                    assistanceLevel = AssistanceLevel.REDUCED,
                ),
                StageAttempt(
                    objectiveId = "practice-${stage2.id.value}",
                    outcome = AttemptOutcome.COMPLETED,
                    assistanceLevel = AssistanceLevel.REDUCED,
                ),
            )
        val progress = Mastery.evaluate(stage2, attempts, defaultConfig)
        assertEquals(1, progress.solvesCompleted)
        assertEquals(1, progress.reducedHelpSolves)
        assertFalse(progress.isMastered)
    }

    @Test
    fun `attempt from other Stage does not count toward current Stage`() {
        val attempts =
            listOf(
                StageAttempt(
                    objectiveId = "practice-${stage3.id.value}",
                    outcome = AttemptOutcome.COMPLETED,
                    assistanceLevel = AssistanceLevel.REDUCED,
                ),
                StageAttempt(
                    objectiveId = "practice-${stage3.id.value}",
                    outcome = AttemptOutcome.COMPLETED,
                    assistanceLevel = AssistanceLevel.REDUCED,
                ),
            )
        val progress = Mastery.evaluate(stage2, attempts, defaultConfig)
        assertEquals(0, progress.solvesCompleted)
        assertEquals(0, progress.reducedHelpSolves)
        assertFalse(progress.isMastered)
    }

    @Test
    fun `objectiveId matching recognizes practice prefix, bare stageId, and final lesson`() {
        val lastLessonId =
            stage2.lessons
                .last()
                .id.value
        val attempts =
            listOf(
                StageAttempt(objectiveId = "practice-${stage2.id.value}"),
                StageAttempt(objectiveId = stage2.id.value),
                StageAttempt(objectiveId = lastLessonId),
            )
        attempts.forEach { attempt ->
            assertTrue(Mastery.matchesStage(attempt, stage2), "Should match: ${attempt.objectiveId}")
            assertFalse(Mastery.matchesStage(attempt, stage3), "Should not match other stage: ${attempt.objectiveId}")
        }
        val nonFinalLesson =
            StageAttempt(
                objectiveId =
                    stage2.lessons
                        .first()
                        .id.value,
            )
        assertFalse(Mastery.matchesStage(nonFinalLesson, stage2), "Earlier lesson should not match stage mastery attempt")
    }

    @Test
    fun `progress reset isolates active epoch attempts`() {
        val t0 = Instant.fromEpochMilliseconds(1000)
        val t1 = Instant.fromEpochMilliseconds(2000)
        val t2 = Instant.fromEpochMilliseconds(3000)

        val attempts =
            listOf(
                StageAttempt(objectiveId = "practice-${stage2.id.value}", at = t0),
                StageAttempt(objectiveId = "practice-${stage2.id.value}", at = t0),
            )
        val resetEvent = ProgressEvent.ProgressReset(epoch = 1L, at = t1)

        val progressBefore = Mastery.evaluate(stage2, attempts, emptyList(), defaultConfig)
        assertTrue(progressBefore.isMastered)

        val progressAfter = Mastery.evaluate(stage2, attempts, listOf(resetEvent), defaultConfig)
        assertEquals(0, progressAfter.solvesCompleted)
        assertFalse(progressAfter.isMastered)

        val exactResetAttempt = listOf(StageAttempt(objectiveId = "practice-${stage2.id.value}", at = t1))
        val progressAtReset = Mastery.evaluate(stage2, exactResetAttempt, listOf(resetEvent), defaultConfig)
        assertEquals(0, progressAtReset.solvesCompleted, "Attempt at exact reset timestamp must not leak into new epoch")

        val freshAttempts =
            attempts +
                listOf(
                    StageAttempt(objectiveId = "practice-${stage2.id.value}", at = t2),
                    StageAttempt(objectiveId = "practice-${stage2.id.value}", at = t2),
                )
        val progressWithNewAttempts = Mastery.evaluate(stage2, freshAttempts, listOf(resetEvent), defaultConfig)
        assertEquals(2, progressWithNewAttempts.solvesCompleted)
        assertTrue(progressWithNewAttempts.isMastered)
    }

    @Test
    fun `neither lesson completion nor skip marks stage mastered`() {
        val allLessonEvents = stage2.lessons.map { ProgressEvent.LessonCompleted(it.id) }
        val statusCompleted = StageProgress.status(stage2, stages, allLessonEvents, mastered = emptySet())
        assertEquals(StageStatus.COMPLETED, statusCompleted)

        val skipEvent = listOf(ProgressEvent.StageSkippedByCheck(stage2.id))
        val statusSkipped = StageProgress.status(stage2, stages, skipEvent, mastered = emptySet())
        assertEquals(StageStatus.SKIPPED, statusSkipped)

        val placementEvent = listOf(ProgressEvent.StageSkippedByPlacement(stage2.id))
        val statusPlacement = StageProgress.status(stage2, stages, placementEvent, mastered = emptySet())
        assertEquals(StageStatus.SKIPPED_BY_PLACEMENT, statusPlacement)

        val statusMastered = StageProgress.status(stage2, stages, emptyList(), mastered = setOf(stage2.id))
        assertEquals(StageStatus.MASTERED, statusMastered)
    }

    @Test
    fun `custom config thresholds evaluate according to configured numbers`() {
        val customConfig = MasteryThresholds(solvesRequired = 3, reducedHelpRequired = 2)
        val twoReduced =
            listOf(
                StageAttempt(objectiveId = "practice-${stage2.id.value}", assistanceLevel = AssistanceLevel.REDUCED),
                StageAttempt(objectiveId = "practice-${stage2.id.value}", assistanceLevel = AssistanceLevel.REDUCED),
            )
        assertFalse(Mastery.evaluate(stage2, twoReduced, customConfig).isMastered)

        val threeSolvesOneReduced =
            twoReduced.take(1) +
                listOf(
                    StageAttempt(objectiveId = "practice-${stage2.id.value}", assistanceLevel = AssistanceLevel.USUAL),
                    StageAttempt(objectiveId = "practice-${stage2.id.value}", assistanceLevel = AssistanceLevel.USUAL),
                )
        assertFalse(Mastery.evaluate(stage2, threeSolvesOneReduced, customConfig).isMastered)

        val threeSolvesTwoReduced =
            twoReduced +
                listOf(
                    StageAttempt(objectiveId = "practice-${stage2.id.value}", assistanceLevel = AssistanceLevel.USUAL),
                )
        assertTrue(Mastery.evaluate(stage2, threeSolvesTwoReduced, customConfig).isMastered)
    }

    @Test
    fun `masteredStages derives the set of mastered stage IDs`() {
        val attempts =
            listOf(
                StageAttempt(objectiveId = "practice-${stage2.id.value}", assistanceLevel = AssistanceLevel.REDUCED),
                StageAttempt(objectiveId = "practice-${stage2.id.value}", assistanceLevel = AssistanceLevel.REDUCED),
            )
        val mastered = Mastery.masteredStages(stages, attempts, emptyList(), defaultConfig)
        assertEquals(setOf(stage2.id), mastered)
    }
}
