package dev.cubentia.app.session

import dev.cubentia.app.data.SerialWriter
import dev.cubentia.application.port.AlgorithmStatusRecord
import dev.cubentia.application.port.AlgorithmStatusRepository
import dev.cubentia.application.port.AlgorithmStatusSave
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

                    override suspend fun append(record: AlgorithmStatusRecord): AlgorithmStatusSave {
                        writes++
                        if (fail) return AlgorithmStatusSave.Failed
                        statuses.value = statuses.value + (record.event.algorithmId to record.event.status)
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

    @Test fun eachQueuedCommandGetsItsOwnKeyInOneAppRun() =
        runBlocking {
            val records = mutableListOf<AlgorithmStatusRecord>()
            val repo =
                object : AlgorithmStatusRepository {
                    override fun statuses() = MutableStateFlow(emptyMap<AlgorithmId, AlgorithmStatus>())

                    override suspend fun append(record: AlgorithmStatusRecord): AlgorithmStatusSave {
                        records.add(record)
                        return AlgorithmStatusSave.Saved
                    }
                }
            val writer = SerialWriter()
            try {
                val id = AlgorithmId("a")
                val run = SessionHost(TickingClock(), Dispatchers.Default, writer = writer, algorithmStatusRepository = repo)
                val nextRun = SessionHost(TickingClock(), Dispatchers.Default, writer = writer, algorithmStatusRepository = repo)
                val blocked = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                writer.enqueue {
                    blocked.complete(Unit)
                    release.await()
                }
                blocked.await()
                val queued =
                    listOf(AlgorithmStatus.LEARNING, AlgorithmStatus.LEARNING).map {
                        async(start = CoroutineStart.UNDISPATCHED) { run.setAlgorithmStatus(id, it) }
                    }
                release.complete(Unit)
                assertTrue(queued.all { it.await() })
                assertTrue(nextRun.setAlgorithmStatus(id, AlgorithmStatus.LEARNED))

                assertEquals(listOf(0L, 1L, 0L), records.map { it.revision })
                assertEquals(records[0].sessionId, records[1].sessionId)
                assertNotEquals(records[0].sessionId, records[2].sessionId)
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
