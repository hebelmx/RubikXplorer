package dev.cubentia.domain.tutor

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class TwoLookMasteryTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val attempt = StageAttempt("practice-two-look", hints = 0, errors = 0, at = Instant.fromEpochMilliseconds(10))

    private fun progress(
        attempts: List<MasteryAttempt> = emptyList(),
        events: List<ProgressEvent> = emptyList(),
    ) = TwoLookMastery.evaluate(bundle.twoLookStages, attempts, events, 2)

    @Test fun `status order is mastered then completed then in progress`() {
        assertEquals(StageStatus.IN_PROGRESS, progress().status)
        val done = bundle.twoLookStages.flatMap { it.lessons }.map { ProgressEvent.LessonCompleted(it.id) }
        assertEquals(StageStatus.COMPLETED, progress(events = done).status)
        assertEquals(1, progress(listOf(attempt), done).qualifyingSolves)
        assertEquals(StageStatus.COMPLETED, progress(listOf(attempt), done).status)
        assertEquals(StageStatus.MASTERED, progress(listOf(attempt, attempt)).status)
        assertTrue(progress(listOf(attempt, attempt), done).mastered)
    }

    @Test fun `only completed two look attempts with zero hints and errors qualify`() {
        assertTrue(TwoLookMastery.qualifies(attempt))
        listOf(
            attempt.copy(hints = 1),
            attempt.copy(hints = null),
            attempt.copy(errors = 1),
            attempt.copy(errors = null),
            attempt.copy(outcome = AttemptOutcome.ABANDONED),
            attempt.copy(outcome = null),
            attempt.copy(objectiveId = "practice-stage-07-independent-practice"),
        ).forEach { assertFalse(TwoLookMastery.qualifies(it)) }
    }

    @Test fun `the configured solve count sets the mastery boundary`() {
        val two = TwoLookMastery.evaluate(bundle.twoLookStages, listOf(attempt, attempt), emptyList(), 3)
        assertFalse(two.mastered)
        assertEquals(StageStatus.IN_PROGRESS, two.status)
        assertTrue(TwoLookMastery.evaluate(bundle.twoLookStages, listOf(attempt, attempt, attempt), emptyList(), 3).mastered)
    }

    @Test fun `reset clears lessons and attempts including attempts at reset time`() {
        val events =
            bundle.twoLookStages.flatMap { it.lessons }.map { ProgressEvent.LessonCompleted(it.id) } +
                ProgressEvent.ProgressReset(1, attempt.at)
        val progress = progress(listOf(attempt, attempt), events)
        assertEquals(StageStatus.IN_PROGRESS, progress.status)
        assertEquals(0, progress.qualifyingSolves)
        assertFalse(progress.completed)
    }
}
