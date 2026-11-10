package dev.cubentia.domain.tutor

import dev.cubentia.domain.content.LessonId
import dev.cubentia.domain.content.Stage

data class TwoLookProgress(
    val completed: Boolean,
    val qualifyingSolves: Int,
    val solvesRequired: Int,
    val mastered: Boolean,
    val status: StageStatus,
)

/** Mastery requires every 2-look Lesson completed and qualifying solves, including pre-completion solves (SCP 2026-11-10). */
object TwoLookMastery {
    val PRACTICE_ID = LessonId("practice-two-look")

    fun qualifies(attempt: MasteryAttempt): Boolean =
        attempt.objectiveId == PRACTICE_ID.value && attempt.outcome == AttemptOutcome.COMPLETED &&
            attempt.hints == 0 && attempt.errors == 0

    fun evaluate(
        twoLookStages: List<Stage>,
        attempts: List<MasteryAttempt>,
        events: List<ProgressEvent>,
        solvesRequired: Int,
    ): TwoLookProgress {
        val done =
            StageProgress
                .currentEpochEvents(events)
                .filterIsInstance<ProgressEvent.LessonCompleted>()
                .map { it.lessonId }
                .toSet()
        val lessons = twoLookStages.flatMap { it.lessons }
        val completed = lessons.isNotEmpty() && lessons.all { it.id in done }
        val count = Mastery.currentEpochAttempts(attempts, events).count(::qualifies)
        // Qualifying solves before Lesson completion count toward mastery (SCP 2026-11-10).
        val mastered = completed && count >= solvesRequired
        val status =
            when {
                mastered -> StageStatus.MASTERED
                completed -> StageStatus.COMPLETED
                else -> StageStatus.IN_PROGRESS
            }
        return TwoLookProgress(completed, count, solvesRequired, mastered, status)
    }
}
