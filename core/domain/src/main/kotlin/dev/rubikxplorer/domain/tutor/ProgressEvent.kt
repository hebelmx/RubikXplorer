package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.LessonId
import dev.rubikxplorer.domain.content.StageId
import kotlin.time.Instant

/**
 * An append-only Stage progress event (FR-4, FR-19, FR-20, AD-10, AR-14, AR-21).
 *
 * Rows in `progress_event` are never updated or deleted; [ProgressReset] opens a new [epoch] so
 * [StageProgress] derives Stage status purely from the current epoch's events.
 */
sealed interface ProgressEvent {
    val at: Instant

    data class LessonCompleted(
        val lessonId: LessonId,
        override val at: Instant = Instant.fromEpochMilliseconds(0),
    ) : ProgressEvent

    data class StageSkippedByCheck(
        val stageId: StageId,
        override val at: Instant = Instant.fromEpochMilliseconds(0),
    ) : ProgressEvent

    data class StageSkippedByPlacement(
        val stageId: StageId,
        override val at: Instant = Instant.fromEpochMilliseconds(0),
    ) : ProgressEvent

    data class StageUnlocked(
        val stageId: StageId,
        override val at: Instant = Instant.fromEpochMilliseconds(0),
    ) : ProgressEvent

    data class ProgressReset(
        val epoch: Long,
        override val at: Instant = Instant.fromEpochMilliseconds(0),
    ) : ProgressEvent
}
