package dev.cubentia.application.usecase

import dev.cubentia.application.port.AlgorithmStatusRecord
import dev.cubentia.application.port.AlgorithmStatusRepository
import dev.cubentia.application.port.AlgorithmStatusSave
import dev.cubentia.application.port.AlgorithmStatusWrite
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.AlgorithmStatusEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class SetAlgorithmStatusTest {
    @Test fun `each request appends exactly one event and reports save result`() =
        runBlocking {
            val records = mutableListOf<AlgorithmStatusRecord>()
            var save: AlgorithmStatusSave = AlgorithmStatusSave.Saved
            val repository =
                object : AlgorithmStatusRepository {
                    override fun statuses() = flowOf(emptyMap<AlgorithmId, AlgorithmStatus>())

                    override suspend fun append(record: AlgorithmStatusRecord): AlgorithmStatusSave {
                        records.add(record)
                        return save
                    }
                }
            val event = AlgorithmStatusEvent(AlgorithmId("a"), AlgorithmStatus.LEARNING, Instant.fromEpochMilliseconds(7))
            val set = SetAlgorithmStatus(repository)
            val record = AlgorithmStatusRecord("run", 3L, event)
            assertEquals(AlgorithmStatusWrite.Written(event), set(event.algorithmId, event.status, event.at, "run", 3L))
            assertEquals(listOf(record), records)
            save = AlgorithmStatusSave.Failed
            assertEquals(AlgorithmStatusWrite.Failed, set(event.algorithmId, event.status, event.at, "run", 3L))
            assertEquals(listOf(record, record), records)
        }
}
