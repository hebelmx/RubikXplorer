package dev.cubentia.app.camera

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.cubentia.app.camera.recorder.session.DebugContinuousSessionWriterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Optional
import javax.inject.Inject

/** Story 10.34 (AD-9 Frames): the debug graph binds the continuous session writer behind its optional seam. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class ContinuousSessionWriterBindingTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject internal lateinit var writers: Optional<ContinuousSessionWriterFactory>

    @Inject internal lateinit var engines: Optional<ContinuousScanEngineFactory>

    @Before
    fun setup() = hilt.inject()

    @Test
    fun debugGraphBindsTheContinuousSessionWriter() {
        assertTrue("Optional<ContinuousSessionWriterFactory> is empty in the debug graph", writers.isPresent)
        assertTrue("Bound ${writers.get()::class}", writers.get() is DebugContinuousSessionWriterFactory)
    }

    @Test
    fun theDebugBuildInfoAssetCarriesTheCommitAndLiteRtVersion() {
        val info =
            dev.cubentia.app.camera.recorder.session.SessionFiles
                .buildInfo(
                    androidx.test.core.app.ApplicationProvider
                        .getApplicationContext(),
                )
        // Inside a git checkout (a .git dir or worktree file at the repo root) the stamp must be the full commit.
        val inGit = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.any { java.io.File(it, ".git").exists() }
        val pattern = if (inGit) Regex("[0-9a-f]{40}") else Regex("[0-9a-f]{40}|unknown")
        assertTrue("commit=${info["commit"]}", info["commit"].orEmpty().matches(pattern))
        assertTrue("litert=${info["litert"]}", info["litert"].orEmpty().isNotBlank())
    }

    @Test
    fun theContinuousEngineNamesTheBundledModel() {
        val identity = checkNotNull(engines.get().modelIdentity)
        assertEquals(
            dev.cubentia.app.camera.continuous.StickerCenterModelContract.MODEL_SHA256,
            identity.sha256,
        )
        assertEquals("XNNPACK", identity.delegate)
        assertEquals(2, identity.threads)
    }
}
