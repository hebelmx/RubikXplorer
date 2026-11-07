package dev.rubikxplorer.application.usecase

import dev.rubikxplorer.application.port.ProgressLoad
import dev.rubikxplorer.application.port.ProgressRecord
import dev.rubikxplorer.application.port.ProgressRepository
import dev.rubikxplorer.application.port.ProgressSave
import dev.rubikxplorer.application.port.ProgressWrite
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.Stage
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.tutor.ProgressEvent
import dev.rubikxplorer.domain.tutor.StageProgress
import kotlin.time.Instant

/**
 * Single writer for [ProgressEvent.LessonCompleted] (FR-4, AD-10, AR-14): verifies
 * `lesson.completion.isSatisfied(state)` (Completion Predicate only, never Move count), appends
 * [ProgressEvent.LessonCompleted], and appends [ProgressEvent.StageUnlocked] for any Stage in
 * [stages] that transitions from `LOCKED` to unlocked.
 */
class CompleteLesson(
    private val repository: ProgressRepository,
) {
    suspend operator fun invoke(
        lesson: Lesson,
        state: CubeState,
        stages: List<Stage> = emptyList(),
        at: Instant = Instant.fromEpochMilliseconds(0),
        sessionId: String = "",
        revision: Long = -1L,
    ): ProgressWrite {
        if (!lesson.completion.isSatisfied(state)) return ProgressWrite.PredicateNotMet
        val loaded =
            when (val result = repository.all()) {
                is ProgressLoad.Found -> result
                ProgressLoad.Failed -> return ProgressWrite.Failed
            }
        val existing = loaded.events
        val completed = ProgressEvent.LessonCompleted(lesson.id, at)
        val unlocked =
            StageProgress
                .newlyUnlocked(stages, existing, existing + completed)
                .map { stageId -> ProgressEvent.StageUnlocked(stageId, at) }
        val newEvents = listOf(completed) + unlocked
        val epoch = StageProgress.currentEpoch(existing)
        val actualSessionId = sessionId.ifEmpty { "lesson:${lesson.id.value}:epoch-$epoch" }
        val actualRevision = if (revision >= 0L) revision else 0L
        val records = newEvents.map { event -> ProgressRecord(actualSessionId, actualRevision, event) }
        return when (repository.append(records)) {
            ProgressSave.Saved -> ProgressWrite.Written(newEvents)
            ProgressSave.Failed -> ProgressWrite.Failed
        }
    }
}
