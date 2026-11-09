package dev.cubentia.app.data

import androidx.room3.Dao
import androidx.room3.Query
import dev.cubentia.app.data.ProgressEventEntity.Companion.TYPE_PROGRESS_RESET
import kotlinx.coroutines.flow.Flow

@Dao
interface AlgorithmStatusDao {
    @Query(
        """INSERT OR IGNORE INTO algorithm_status_event (session_id, revision, algorithm_id, status, epoch, at_ms)
        SELECT :sessionId, :revision, :algorithmId, :status,
        COALESCE((SELECT MAX(epoch) FROM progress_event WHERE type = '$TYPE_PROGRESS_RESET'), 0), :atMs""",
    )
    suspend fun insert(
        sessionId: String,
        revision: Long,
        algorithmId: String,
        status: String,
        atMs: Long,
    )

    @Query(
        """SELECT * FROM algorithm_status_event WHERE epoch =
        COALESCE((SELECT MAX(epoch) FROM progress_event WHERE type = '$TYPE_PROGRESS_RESET'), 0) ORDER BY seq""",
    )
    fun currentEpoch(): Flow<List<AlgorithmStatusEventEntity>>
}
