package dev.cubentia.app.session

import dev.cubentia.app.data.SerialWriter
import dev.cubentia.application.port.AlgorithmStatusRepository
import dev.cubentia.application.port.AlgorithmStatusSave
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.AlgorithmStatusEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHostAlgorithmStatusTest {
    @Test fun writesAreSerializedAndFailureKeepsPreviousStatus() =
        runBlocking {
            val id = AlgorithmId("a")
            val statuses = MutableStateFlow(mapOf(id to AlgorithmStatus.LEARNING))
            var fail = true
            var writes = 0
            val repo =
                object : AlgorithmStatusRepository {
                    override fun statuses() = statuses

                    override suspend fun append(event: AlgorithmStatusEvent): AlgorithmStatusSave {
                        writes++
                        if (fail) return AlgorithmStatusSave.Failed
                        statuses.value = statuses.value + (event.algorithmId to event.status)
                        return AlgorithmStatusSave.Saved
                    }
                }
            val writer = SerialWriter()
            try {
                val host = SessionHost(TickingClock(), Dispatchers.Default, writer = writer, algorithmStatusRepository = repo)
                val blocked = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                writer.enqueue {
                    blocked.complete(Unit)
                    release.await()
                }
                blocked.await()
                val result = async(start = CoroutineStart.UNDISPATCHED) { host.setAlgorithmStatus(id, AlgorithmStatus.LEARNED) }
                assertEquals(0, writes)
                assertFalse(result.isCompleted)
                release.complete(Unit)
                assertFalse(result.await())
                assertEquals(mapOf(id to AlgorithmStatus.LEARNING), host.algorithmStatuses.first())
                fail = false
                assertTrue(host.setAlgorithmStatus(id, AlgorithmStatus.LEARNED))
                assertEquals(2, writes)
                assertEquals(mapOf(id to AlgorithmStatus.LEARNED), host.algorithmStatuses.first())
            } finally {
                writer.close()
            }
        }

    @Test fun inMemoryResetClearsStatusesWithoutChangingSession() =
        runBlocking {
            val host = SessionHost(TickingClock())
            val before = host.state.value
            assertTrue(host.setAlgorithmStatus(AlgorithmId("a"), AlgorithmStatus.LEARNING))
            assertTrue(host.resetProgress())
            assertEquals(emptyMap<AlgorithmId, AlgorithmStatus>(), host.algorithmStatuses.first())
            assertEquals(before, host.state.value)
        }
}
