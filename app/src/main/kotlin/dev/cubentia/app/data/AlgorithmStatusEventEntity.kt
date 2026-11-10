package dev.cubentia.app.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.AlgorithmStatusEvent
import kotlin.time.Instant

@Entity(
    tableName = "algorithm_status_event",
    indices = [
        Index("algorithm_id"),
        Index(value = ["session_id", "revision"], unique = true),
    ],
)
data class AlgorithmStatusEventEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val revision: Long,
    @ColumnInfo(name = "algorithm_id") val algorithmId: String,
    val status: String,
    val epoch: Long,
    @ColumnInfo(name = "at_ms") val atMs: Long,
) {
    fun event(): AlgorithmStatusEvent? {
        val decoded = AlgorithmStatus.entries.firstOrNull { it.name == status } ?: return null
        return AlgorithmStatusEvent(AlgorithmId(algorithmId), decoded, Instant.fromEpochMilliseconds(atMs))
    }
}
