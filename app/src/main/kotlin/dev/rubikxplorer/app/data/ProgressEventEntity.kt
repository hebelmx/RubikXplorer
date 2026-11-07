package dev.rubikxplorer.app.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.rubikxplorer.application.port.ProgressRecord
import dev.rubikxplorer.domain.content.LessonId
import dev.rubikxplorer.domain.content.StageId
import dev.rubikxplorer.domain.tutor.ProgressEvent
import kotlin.time.Instant

@Entity(
    tableName = "progress_event",
    indices = [
        Index(
            value = ["session_id", "revision", "type", "stage_id", "lesson_id"],
            unique = true,
        ),
    ],
)
data class ProgressEventEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val revision: Long,
    val type: String,
    @ColumnInfo(name = "stage_id") val stageId: String = "",
    @ColumnInfo(name = "lesson_id") val lessonId: String = "",
    val epoch: Long? = null,
    @ColumnInfo(name = "at_ms") val atMs: Long,
) {
    fun record(): ProgressRecord? {
        val at = Instant.fromEpochMilliseconds(atMs)
        val event =
            when (type) {
                TYPE_LESSON_COMPLETED -> {
                    if (lessonId.isEmpty()) return null
                    ProgressEvent.LessonCompleted(LessonId(lessonId), at)
                }

                TYPE_STAGE_SKIPPED_BY_CHECK -> {
                    if (stageId.isEmpty()) return null
                    ProgressEvent.StageSkippedByCheck(StageId(stageId), at)
                }

                TYPE_STAGE_SKIPPED_BY_PLACEMENT -> {
                    if (stageId.isEmpty()) return null
                    ProgressEvent.StageSkippedByPlacement(StageId(stageId), at)
                }

                TYPE_STAGE_UNLOCKED -> {
                    if (stageId.isEmpty()) return null
                    ProgressEvent.StageUnlocked(StageId(stageId), at)
                }

                TYPE_PROGRESS_RESET -> {
                    ProgressEvent.ProgressReset(epoch ?: return null, at)
                }

                else -> {
                    return null
                }
            }
        return ProgressRecord(sessionId, revision, event)
    }

    companion object {
        const val TYPE_LESSON_COMPLETED = "LESSON_COMPLETED"
        const val TYPE_STAGE_SKIPPED_BY_CHECK = "STAGE_SKIPPED_BY_CHECK"
        const val TYPE_STAGE_SKIPPED_BY_PLACEMENT = "STAGE_SKIPPED_BY_PLACEMENT"
        const val TYPE_STAGE_UNLOCKED = "STAGE_UNLOCKED"
        const val TYPE_PROGRESS_RESET = "PROGRESS_RESET"

        fun of(record: ProgressRecord): ProgressEventEntity {
            val atMs = record.event.at.toEpochMilliseconds()
            return when (val event = record.event) {
                is ProgressEvent.LessonCompleted -> {
                    ProgressEventEntity(
                        sessionId = record.sessionId,
                        revision = record.revision,
                        type = TYPE_LESSON_COMPLETED,
                        lessonId = event.lessonId.value,
                        atMs = atMs,
                    )
                }

                is ProgressEvent.StageSkippedByCheck -> {
                    ProgressEventEntity(
                        sessionId = record.sessionId,
                        revision = record.revision,
                        type = TYPE_STAGE_SKIPPED_BY_CHECK,
                        stageId = event.stageId.value,
                        atMs = atMs,
                    )
                }

                is ProgressEvent.StageSkippedByPlacement -> {
                    ProgressEventEntity(
                        sessionId = record.sessionId,
                        revision = record.revision,
                        type = TYPE_STAGE_SKIPPED_BY_PLACEMENT,
                        stageId = event.stageId.value,
                        atMs = atMs,
                    )
                }

                is ProgressEvent.StageUnlocked -> {
                    ProgressEventEntity(
                        sessionId = record.sessionId,
                        revision = record.revision,
                        type = TYPE_STAGE_UNLOCKED,
                        stageId = event.stageId.value,
                        atMs = atMs,
                    )
                }

                is ProgressEvent.ProgressReset -> {
                    ProgressEventEntity(
                        sessionId = record.sessionId,
                        revision = record.revision,
                        type = TYPE_PROGRESS_RESET,
                        epoch = event.epoch,
                        atMs = atMs,
                    )
                }
            }
        }
    }
}
