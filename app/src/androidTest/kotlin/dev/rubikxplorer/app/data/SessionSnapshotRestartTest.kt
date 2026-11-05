package dev.rubikxplorer.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionSnapshotRestartTest {
    @Test fun lessonResumesAcrossFileDatabaseRestart() =
        runBlocking {
            snapshotRestartScenario(
                androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext,
            )
        }

    @Test fun solvePlaybackDoesNotResume() =
        runBlocking {
            snapshotRestartScenario(
                androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext,
                playback = true,
            )
        }

    @Test fun changedContentDoesNotResume() =
        runBlocking {
            snapshotRestartScenario(
                androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext,
                changed = true,
            )
        }

    @Test fun unresolvedLessonDoesNotResume() =
        runBlocking {
            snapshotRestartScenario(
                androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext,
                missing = true,
            )
        }
}
