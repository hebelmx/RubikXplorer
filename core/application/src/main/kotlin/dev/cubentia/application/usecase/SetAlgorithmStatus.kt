package dev.cubentia.application.usecase

import dev.cubentia.application.port.AlgorithmStatusLoad
import dev.cubentia.application.port.AlgorithmStatusRecord
import dev.cubentia.application.port.AlgorithmStatusRepository
import dev.cubentia.application.port.AlgorithmStatusSave
import dev.cubentia.application.port.AlgorithmStatusWrite
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.AlgorithmStatusEvent
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

class SetAlgorithmStatus(
    private val repository: AlgorithmStatusRepository,
) {
    suspend operator fun invoke(
        algorithmId: AlgorithmId,
        status: AlgorithmStatus,
        at: Instant,
        sessionId: String,
        revision: Long,
    ): AlgorithmStatusWrite {
        val event = AlgorithmStatusEvent(algorithmId, status, at)
        return when (repository.append(AlgorithmStatusRecord(sessionId, revision, event))) {
            AlgorithmStatusSave.Saved -> AlgorithmStatusWrite.Written(event)
            AlgorithmStatusSave.Failed -> AlgorithmStatusWrite.Failed
        }
    }

    /**
     * Moves [algorithmId] to *Learning* only when its persisted status is still *Not started*; any other
     * status, including one written after a screen last read the statuses, is left [AlgorithmStatusWrite.Unchanged].
     * Run it inside the single writer so the read and the append are one step.
     */
    suspend fun startLearning(
        algorithmId: AlgorithmId,
        at: Instant,
        sessionId: String,
        revision: Long,
    ): AlgorithmStatusWrite {
        val statuses = (repository.statuses().first() as? AlgorithmStatusLoad.Loaded)?.statuses ?: return AlgorithmStatusWrite.Failed
        if ((statuses[algorithmId] ?: AlgorithmStatus.NOT_STARTED) != AlgorithmStatus.NOT_STARTED) return AlgorithmStatusWrite.Unchanged
        return invoke(algorithmId, AlgorithmStatus.LEARNING, at, sessionId, revision)
    }
}
