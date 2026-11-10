package dev.cubentia.app.session

import dev.cubentia.app.data.SerialWriter
import dev.cubentia.app.ui.shell.algorithmsToOffer
import dev.cubentia.application.port.AlgorithmStatusLoad
import dev.cubentia.application.port.AlgorithmStatusRecord
import dev.cubentia.application.port.AlgorithmStatusRepository
import dev.cubentia.application.port.AlgorithmStatusSave
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
                    override fun statuses() =
                        statuses.map {
                            dev.cubentia.application.port.AlgorithmStatusLoad
                                .Loaded(it)
                        }

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
                    override fun statuses() =
                        MutableStateFlow(
                            dev.cubentia.application.port.AlgorithmStatusLoad
                                .Loaded(emptyMap<AlgorithmId, AlgorithmStatus>()),
                        )

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

    @Test fun acceptanceNeverOverwritesAStatusTheOfferHadNotSeen() =
        runBlocking {
            val learned = AlgorithmId("learned")
            val fresh = AlgorithmId("fresh")
            val persisted = MutableStateFlow(emptyMap<AlgorithmId, AlgorithmStatus>())
            val records = mutableListOf<AlgorithmStatusRecord>()
            val repo =
                object : AlgorithmStatusRepository {
                    // Each emission arrives late, so the offer below was built from a map that is already stale.
                    override fun statuses() =
                        flow {
                            delay(50)
                            emit(AlgorithmStatusLoad.Loaded(persisted.value))
                        }

                    override suspend fun append(record: AlgorithmStatusRecord): AlgorithmStatusSave {
                        records.add(record)
                        persisted.value = persisted.value + (record.event.algorithmId to record.event.status)
                        return AlgorithmStatusSave.Saved
                    }
                }
            val writer = SerialWriter()
            try {
                val host = SessionHost(TickingClock(), Dispatchers.Default, writer = writer, algorithmStatusRepository = repo)
                val offered = algorithmsToOffer(listOf(learned, fresh), AlgorithmStatusLoad.Loaded(emptyMap()))
                assertEquals(listOf(learned, fresh), offered)
                val blocked = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                writer.enqueue {
                    blocked.complete(Unit)
                    release.await()
                }
                blocked.await()
                // A Learned update queued before acceptance, not yet seen by the offer.
                val marked = async(start = CoroutineStart.UNDISPATCHED) { host.setAlgorithmStatus(learned, AlgorithmStatus.LEARNED) }
                val accepted =
                    offered.map { id -> async(start = CoroutineStart.UNDISPATCHED) { host.startLearning(id) } }
                release.complete(Unit)
                assertTrue(marked.await())
                assertTrue(accepted.all { it.await() })
                assertEquals(mapOf(learned to AlgorithmStatus.LEARNED, fresh to AlgorithmStatus.LEARNING), persisted.value)
                assertEquals(listOf(AlgorithmStatus.LEARNED, AlgorithmStatus.LEARNING), records.map { it.event.status })
                // Accepting again changes nothing: neither Algorithm is Not started any more.
                assertTrue(offered.map { host.startLearning(it) }.all { it })
                assertEquals(2, records.size)
            } finally {
                writer.close()
            }
        }

    @Test fun acceptanceFailsWithoutWritingWhenStatusesCannotBeRead() =
        runBlocking {
            var writes = 0
            val repo =
                object : AlgorithmStatusRepository {
                    override fun statuses() = MutableStateFlow<AlgorithmStatusLoad>(AlgorithmStatusLoad.Failed)

                    override suspend fun append(record: AlgorithmStatusRecord): AlgorithmStatusSave {
                        writes++
                        return AlgorithmStatusSave.Saved
                    }
                }
            val writer = SerialWriter()
            try {
                val host = SessionHost(TickingClock(), Dispatchers.Default, writer = writer, algorithmStatusRepository = repo)
                assertFalse(host.startLearning(AlgorithmId("a")))
                assertEquals(0, writes)
            } finally {
                writer.close()
            }
        }

    @Test fun inMemoryAcceptanceOnlyStartsNotStartedAlgorithms() =
        runBlocking {
            val host = SessionHost(TickingClock())
            assertTrue(host.setAlgorithmStatus(AlgorithmId("a"), AlgorithmStatus.LEARNED))
            assertTrue(host.startLearning(AlgorithmId("a")))
            assertTrue(host.startLearning(AlgorithmId("b")))
            assertEquals(
                mapOf(AlgorithmId("a") to AlgorithmStatus.LEARNED, AlgorithmId("b") to AlgorithmStatus.LEARNING),
                host.algorithmStatuses.first(),
            )
        }
}
