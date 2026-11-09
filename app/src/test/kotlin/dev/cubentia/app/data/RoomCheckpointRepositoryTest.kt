package dev.cubentia.app.data

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.cubentia.application.port.CheckpointLoad
import dev.cubentia.application.port.CheckpointSave
import dev.cubentia.application.session.SessionKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomCheckpointRepositoryTest {
    @Test fun replaceCorruptionAndClosedDatabase() =
        runBlocking {
            val db =
                Room
                    .inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            val repo = RoomCheckpointRepository(db)
            val checkpoint = checkpointFixture()
            try {
                assertEquals(CheckpointLoad.None, repo.find(SessionKind.LESSON))
                assertEquals(CheckpointSave.Saved, repo.save(checkpoint))
                assertEquals(CheckpointLoad.Found(checkpoint), repo.find(SessionKind.LESSON))
                val replacement = checkpoint.copy(savedAt = 999)
                assertEquals(CheckpointSave.Saved, repo.save(replacement))
                assertEquals(CheckpointLoad.Found(replacement), repo.find(SessionKind.LESSON))
                assertEquals(CheckpointLoad.None, repo.find(SessionKind.PRACTICE))
                db.checkpoints().replace(CheckpointEntity("LESSON", "bad json"))
                assertEquals(CheckpointLoad.Failed, repo.find(SessionKind.LESSON))
                val wrongKind = CheckpointPayload.encode(checkpoint.copy(kind = SessionKind.PRACTICE))
                db.checkpoints().replace(CheckpointEntity("LESSON", wrongKind))
                assertEquals(CheckpointLoad.Failed, repo.find(SessionKind.LESSON))
            } finally {
                db.close()
            }
            assertEquals(CheckpointSave.Failed, repo.save(checkpoint))
            assertEquals(CheckpointLoad.Failed, repo.find(SessionKind.LESSON))
        }
}
