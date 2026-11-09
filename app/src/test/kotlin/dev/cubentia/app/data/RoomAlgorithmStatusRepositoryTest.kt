package dev.cubentia.app.data

import android.app.Application
import androidx.room3.Room
import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.cubentia.application.port.AlgorithmStatusSave
import dev.cubentia.application.port.AlgorithmStatusWrite
import dev.cubentia.application.usecase.ResetProgress
import dev.cubentia.application.usecase.SetAlgorithmStatus
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.AlgorithmStatusEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomAlgorithmStatusRepositoryTest {
    private val id = AlgorithmId("a")
    private val at = Instant.fromEpochMilliseconds(0)

    private suspend fun scalar(
        db: RubikDatabase,
        sql: String,
    ): Long =
        db.withReadTransaction {
            usePrepared(sql) {
                it.step()
                it.getLong(0)
            }
        }

    @Test fun `events survive restart reset preserves rows and new writes use new epoch`() =
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val name = "algorithm-status-restart.db"
            app.deleteDatabase(name)

            fun open() = Room.databaseBuilder<RubikDatabase>(app, name).setDriver(AndroidSQLiteDriver()).build()
            val first = open()
            try {
                val set = SetAlgorithmStatus(RoomAlgorithmStatusRepository(first))
                assertEquals(
                    AlgorithmStatusWrite.Written(AlgorithmStatusEvent(id, AlgorithmStatus.LEARNING, at)),
                    set(id, AlgorithmStatus.LEARNING, at),
                )
                set(id, AlgorithmStatus.LEARNED, at)
                first.algorithmStatuses().insert("unknown", "FUTURE_STATUS", 0)
                assertEquals(3L, scalar(first, "SELECT COUNT(*) FROM algorithm_status_event"))
            } finally {
                first.close()
            }
            val db = open()
            try {
                val repo = RoomAlgorithmStatusRepository(db)
                assertEquals(mapOf(id to AlgorithmStatus.LEARNED), repo.statuses().first())
                ResetProgress(RoomProgressRepository(db))(at)
                assertEquals(emptyMap<AlgorithmId, AlgorithmStatus>(), repo.statuses().first())
                assertEquals(3L, scalar(db, "SELECT COUNT(*) FROM algorithm_status_event"))
                SetAlgorithmStatus(repo)(id, AlgorithmStatus.NOT_STARTED, at)
                assertEquals(mapOf(id to AlgorithmStatus.NOT_STARTED), repo.statuses().first())
                assertEquals(1L, scalar(db, "SELECT epoch FROM algorithm_status_event ORDER BY seq DESC LIMIT 1"))
                db.withWriteTransaction {
                    usePrepared("DROP TABLE algorithm_status_event") {
                        it.step()
                        Unit
                    }
                }
                assertEquals(AlgorithmStatusSave.Failed, repo.append(AlgorithmStatusEvent(id, AlgorithmStatus.LEARNING, at)))
                assertEquals(emptyMap<AlgorithmId, AlgorithmStatus>(), repo.statuses().first())
            } finally {
                db.close()
                app.deleteDatabase(name)
            }
        }

    @Test fun `flow observes both status writes and progress resets`() =
        runBlocking {
            val db =
                Room
                    .inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            try {
                val repo = RoomAlgorithmStatusRepository(db)
                val initial = CompletableDeferred<Unit>()
                val changed = CompletableDeferred<Unit>()
                val reset = CompletableDeferred<Unit>()
                val job =
                    launch {
                        var index = 0
                        repo.statuses().take(3).collect { map ->
                            when (index++) {
                                0 -> {
                                    assertEquals(emptyMap<AlgorithmId, AlgorithmStatus>(), map)
                                    initial.complete(Unit)
                                }

                                1 -> {
                                    assertEquals(mapOf(id to AlgorithmStatus.LEARNING), map)
                                    changed.complete(Unit)
                                }

                                2 -> {
                                    assertEquals(emptyMap<AlgorithmId, AlgorithmStatus>(), map)
                                    reset.complete(Unit)
                                }
                            }
                        }
                    }
                withTimeout(10000) {
                    initial.await()
                    SetAlgorithmStatus(repo)(id, AlgorithmStatus.LEARNING, at)
                    changed.await()
                    ResetProgress(RoomProgressRepository(db))(at)
                    reset.await()
                    job.join()
                }
            } finally {
                db.close()
            }
        }
}
