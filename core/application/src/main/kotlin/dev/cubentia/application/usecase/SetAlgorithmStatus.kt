package dev.cubentia.application.usecase

import dev.cubentia.application.port.AlgorithmStatusRecord
import dev.cubentia.application.port.AlgorithmStatusRepository
import dev.cubentia.application.port.AlgorithmStatusSave
import dev.cubentia.application.port.AlgorithmStatusWrite
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.AlgorithmStatusEvent
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
}
