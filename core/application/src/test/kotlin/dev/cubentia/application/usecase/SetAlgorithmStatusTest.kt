package dev.cubentia.application.usecase

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
            val events = mutableListOf<AlgorithmStatusEvent>()
            var save: AlgorithmStatusSave = AlgorithmStatusSave.Saved
            val repository =
                object : AlgorithmStatusRepository {
                    override fun statuses() = flowOf(emptyMap<AlgorithmId, AlgorithmStatus>())

                    override suspend fun append(event: AlgorithmStatusEvent): AlgorithmStatusSave {
                        events.add(event)
                        return save
                    }
                }
            val event = AlgorithmStatusEvent(AlgorithmId("a"), AlgorithmStatus.LEARNING, Instant.fromEpochMilliseconds(7))
            val set = SetAlgorithmStatus(repository)
            assertEquals(AlgorithmStatusWrite.Written(event), set(event.algorithmId, event.status, event.at))
            assertEquals(listOf(event), events)
            save = AlgorithmStatusSave.Failed
            assertEquals(AlgorithmStatusWrite.Failed, set(event.algorithmId, event.status, event.at))
            assertEquals(listOf(event, event), events)
        }
}
