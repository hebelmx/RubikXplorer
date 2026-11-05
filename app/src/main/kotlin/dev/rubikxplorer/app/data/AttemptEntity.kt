package dev.rubikxplorer.app.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.rubikxplorer.application.session.AttemptEvent
import dev.rubikxplorer.application.session.AttemptOutcome
import dev.rubikxplorer.application.session.AttemptRecord
import dev.rubikxplorer.domain.tutor.AssistanceLevel
import kotlin.time.Instant

@Entity(tableName = "attempt", indices = [Index(value = ["session_id", "revision"], unique = true)])
data class AttemptEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val revision: Long,
    val event: String,
    @ColumnInfo(name = "objective_id") val objectiveId: String,
    val outcome: String?,
    val errors: Int?,
    val hints: Int?,
    @ColumnInfo(name = "assistance_level") val assistanceLevel: String?,
    @ColumnInfo(name = "at_ms") val atMs: Long,
) {
    fun record(): AttemptRecord =
        AttemptRecord(
            sessionId,
            revision,
            AttemptEvent.valueOf(event),
            objectiveId,
            outcome?.let(AttemptOutcome::valueOf),
            errors,
            hints,
            assistanceLevel?.let(AssistanceLevel::valueOf),
            Instant.fromEpochMilliseconds(atMs),
        )

    companion object {
        fun of(record: AttemptRecord): AttemptEntity =
            AttemptEntity(
                sessionId = record.sessionId,
                revision = record.revision,
                event = record.event.name,
                objectiveId = record.objectiveId,
                outcome = record.outcome?.name,
                errors = record.errors,
                hints = record.hints,
                assistanceLevel = record.assistanceLevel?.name,
                atMs = record.at.toEpochMilliseconds(),
            )
    }
}
