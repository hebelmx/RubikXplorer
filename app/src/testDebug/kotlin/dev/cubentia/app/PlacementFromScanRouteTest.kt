package dev.cubentia.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import dev.cubentia.app.camera.CameraModule
import dev.cubentia.app.camera.GuidedScanActivity
import dev.cubentia.app.camera.GuidedScanPhase
import dev.cubentia.app.camera.GuidedScanViewModel
import dev.cubentia.app.camera.ScanFrameSource
import dev.cubentia.app.di.SolverModule
import dev.cubentia.app.scan.ScanFlowContract
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.solver.SolverLoad
import dev.cubentia.app.solver.SolverSource
import dev.cubentia.app.ui.solve.SolvePhase
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.GuidedScanPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** Story 11.2: Welcome's *Start from my cube* result reaches Placement through MainActivity, never the Solve flow. */
@HiltAndroidTest
@UninstallModules(CameraModule::class, SolverModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp")
class PlacementFromScanRouteTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val camera = ScanRouteTest.FakeFrames()

    @BindValue internal val frames: ScanFrameSource = camera

    private var denied = false

    @BindValue val launcher: ScanLauncher =
        object : ScanLauncher {
            override fun hasCamera(context: Context) = true

            override fun cameraDenied(context: Context) = denied

            override fun guidedScanIntent(context: Context) = Intent(context, GuidedScanActivity::class.java)
        }

    /** SolverModule is uninstalled here, so the method-shaped solve it provides is bound too (Story 15.3). */
    @BindValue val methodSolver: dev.cubentia.app.solver.MethodSolverFactory =
        dev.cubentia.app.solver.MethodSolverFactory {
            dev.cubentia.application.usecase
                .SolveByMethod(it)
        }

    @BindValue val solverSource: SolverSource =
        object : SolverSource {
            override val isReady = false

            override suspend fun load(onGenerating: () -> Unit): SolverLoad = SolverLoad.Failed
        }

    @Before fun setup() {
        hilt.inject()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA)
    }

    @Test fun acceptedScanFromWelcomeShowsThePlacementResult() =
        placementScan(ScanOutcome.Accepted(CubeState.SOLVED)) { main ->
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("placement:prompt").fetchSemanticsNodes().isNotEmpty() }
            rule
                .onNodeWithTag("placement:prompt")
                .assertTextContains("Your cube is solved.")
            assertEquals(SolvePhase.Home, main.solveFlowPhase())
            ShadowLog.clear()
            rule.onNodeWithTag("placement:startStage1").performClick()
            rule.waitForIdle()
            val records = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }.filter { it.startsWith("placement run ") }
            assertEquals(1, records.size)
            val record =
                Regex("""placement run elapsedMs=\d+ outcome=SOLVED suggested=SOLVED accepted=stage-01-fundamentals device=[A-Za-z0-9-]+""")
            assertTrue(records.single(), record.matches(records.single()))
        }

    @Test fun cancelledScanFromWelcomeReturnsToWelcome() =
        placementScan(ScanOutcome.Cancelled) { main ->
            rule.onNodeWithTag("welcome").assertIsDisplayed()
            rule.onNodeWithTag("placement:prompt").assertDoesNotExist()
            assertEquals(SolvePhase.Home, main.solveFlowPhase())
        }

    @Test fun aFallbackWithScannedStickersOpensTheBlankPlacementEditorWithTheOldNote() =
        placementScan(ScanOutcome.FellBackToEditor(CubeState.SOLVED.toFacelets().colors)) { main ->
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("placement:editorNote").fetchSemanticsNodes().isNotEmpty() }
            rule
                .onNodeWithTag("placement:editorNote")
                .assertTextContains("No scan this time. Enter your cube's colors here.")
            assertEquals(SolvePhase.Home, main.solveFlowPhase())
        }

    @Test fun reviewCloseWithNoChangeFromAPlacementScanOpensTheBlankPlacementEditor() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("welcome:startFromCube").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("welcome:startFromCube").performClick()
            val activity = shadowOf(main.get())
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                activity.peekNextStartedActivityForResult() != null
            }
            val started = activity.nextStartedActivityForResult.intent
            val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, started).setup()
            try {
                rule.waitUntil(10_000) {
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))
                    camera.starts > 0
                }
                rule.waitForIdle()
                val viewModel = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
                val colors = CubeState.SOLVED.toFacelets().colors
                GuidedScanPlan.steps.forEach { step ->
                    val start = step.face.ordinal * 9
                    val cells = colors.subList(start, start + 9).map { CellReading(ScanRouteTest.labs.getValue(it), true) }
                    camera.feed(FrameStats(cells, 120.0, 0.0, 100.0, 12), null)
                    rule.waitForIdle()
                    viewModel.frozen?.let { viewModel.endFreeze(it.id) }
                    rule.waitForIdle()
                }
                val stickers = (viewModel.phase as GuidedScanPhase.Review).review.stickers
                rule.onNodeWithTag("review:close").performScrollTo().performClick()
                rule.onNodeWithTag("review:discard").assertDoesNotExist()
                rule.waitForIdle()
                val result = shadowOf(scan.get())
                // Review Close with no change now falls back with the stickers for every caller (GH #321).
                assertEquals(ScanOutcome.FellBackToEditor(stickers), ScanFlowContract.decode(result.resultCode, result.resultIntent))
                activity.receiveResult(started, result.resultCode, result.resultIntent)
            } finally {
                if (scan.get().lifecycle.currentState != Lifecycle.State.DESTROYED) scan.pause().stop().destroy()
            }
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                rule.onAllNodesWithTag("placement:editorNote").fetchSemanticsNodes().isNotEmpty()
            }
            // Placement ignores the stickers: its Editor opens blank with the old note.
            rule
                .onNodeWithTag("placement:editorNote")
                .assertTextContains("No scan this time. Enter your cube's colors here.")
            rule.onNodeWithTag("solve:scanNote").assertDoesNotExist()
            assertEquals(SolvePhase.Home, main.get().solveFlowPhase())
        } finally {
            main.pause().stop().destroy()
        }
    }

    @Test fun recordedDenialFromWelcomeOpensThePlacementEditorAndStartsNoScan() {
        denied = true
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("welcome:startFromCube").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("welcome:startFromCube").performClick()
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                rule.onAllNodesWithTag("placement:editorNote").fetchSemanticsNodes().isNotEmpty()
            }
            rule
                .onNodeWithTag("placement:editorNote")
                .assertTextContains("Camera access is off, so enter your cube's colors here.")
            assertNull(shadowOf(main.get()).peekNextStartedActivityForResult())
            assertEquals(SolvePhase.Home, main.get().solveFlowPhase())
        } finally {
            main.pause().stop().destroy()
        }
    }

    private fun MainActivity.solveFlowPhase(): SolvePhase =
        androidx.lifecycle
            .ViewModelProvider(this)[dev.cubentia.app.ui.solve.SolveFlowViewModel::class.java]
            .phase.value

    private fun placementScan(
        outcome: ScanOutcome,
        body: (MainActivity) -> Unit,
    ) {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("welcome:startFromCube").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("welcome:startFromCube").performClick()
            val activity = shadowOf(main.get())
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                activity.peekNextStartedActivityForResult() != null
            }
            val started = activity.nextStartedActivityForResult.intent
            assertEquals(GuidedScanActivity::class.java.name, started.component?.className)
            // Placement never seeds its Editor (GH #321).
            assertFalse(started.hasExtra(ScanFlowContract.EXTRA_SEEDS_EDITOR))
            val (code, data) = ScanFlowContract.encode(outcome)
            activity.receiveResult(started, code, data)
            rule.waitForIdle()
            body(main.get())
        } finally {
            main.pause().stop().destroy()
        }
    }
}
