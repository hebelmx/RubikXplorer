package dev.cubentia.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
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
import dev.cubentia.app.scan.QuickCheckContract
import dev.cubentia.app.scan.QuickCheckInput
import dev.cubentia.app.scan.ScanFlowContract
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.solver.SolverLoad
import dev.cubentia.app.solver.SolverSource
import dev.cubentia.application.port.CubeSolver
import dev.cubentia.application.port.SolveResult
import dev.cubentia.application.session.SessionKind
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.GuidedScanPlan
import dev.cubentia.domain.recognition.PhysicalObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@HiltAndroidTest
@UninstallModules(CameraModule::class, SolverModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp")
class SolveFromScanRouteTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val scramble =
        listOf(
            FaceTurn(Face.R, Turn.CLOCKWISE),
            FaceTurn(Face.U, Turn.COUNTERCLOCKWISE),
            FaceTurn(Face.F, Turn.HALF),
            FaceTurn(Face.L, Turn.CLOCKWISE),
        )
    private val cube = CubeState.SOLVED.apply(scramble)
    private val camera = ScanRouteTest.FakeFrames()

    @BindValue internal val frames: ScanFrameSource = camera

    @BindValue val launcher: ScanLauncher =
        object : ScanLauncher {
            override fun hasCamera(context: Context) = true

            override fun guidedScanIntent(context: Context) = Intent(context, GuidedScanActivity::class.java)
        }

    @BindValue val solverSource: SolverSource =
        object : SolverSource {
            override val isReady = true

            override suspend fun load(onGenerating: () -> Unit): SolverLoad =
                SolverLoad.Ready(
                    CubeSolver {
                        assertEquals(cube.cubies, it)
                        SolveResult.Solved(scramble.asReversed().map(FaceTurn::inverse))
                    },
                )
        }

    @Before fun setup() {
        hilt.inject()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA)
    }

    @Test fun acceptedScanGoesStraightToPlayback() =
        solveScan { main, scan, started ->
            rule.onNodeWithTag("scanEnterColors").assertIsDisplayed()
            captureCube(scan)
            rule.onNodeWithTag("review:enterColors").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("review:accept").performScrollTo().performClick()
            returnToSolve(main, scan, started, ScanOutcome.Accepted(cube))

            rule.onNodeWithTag("editor:accept").assertDoesNotExist()
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                main.sessionHost.state.value.kind == SessionKind.SOLVE_PLAYBACK
            }
            rule.onNodeWithTag("solvePlayback:screen").assertIsDisplayed()
            rule.onNodeWithTag("solutionPlayback").assertIsDisplayed()
            assertEquals(PhysicalObservation.Full(cube), main.sessionHost.state.value.observed)
        }

    @Test fun solvedScanShowsAlreadySolvedWithoutTheEditor() =
        solveScan { main, scan, started ->
            captureCube(scan, CubeState.SOLVED)
            rule.onNodeWithTag("review:accept").performScrollTo().performClick()
            returnToSolve(main, scan, started, ScanOutcome.Accepted(CubeState.SOLVED))

            rule.onNodeWithTag("editor:accept").assertDoesNotExist()
            rule.onNodeWithTag("solve:alreadySolved").assertIsDisplayed()
            rule.onNodeWithTag("solvePlayback:screen").assertDoesNotExist()
            assertNotEquals(SessionKind.SOLVE_PLAYBACK, main.sessionHost.state.value.kind)
        }

    @Test fun manualEntryFromTheViewfinderReturnsToTheEditorWithTheFallbackNote() =
        solveScan { main, scan, started ->
            rule.onNodeWithTag("scanEnterColors").assertIsDisplayed().performClick()
            returnToSolve(main, scan, started, ScanOutcome.FellBackToEditor)
            rule.onNodeWithTag("solve:scanNote").assertTextEquals("No scan this time. Enter your cube's colors here.")
            rule.onNodeWithTag("editor:accept").assertExists()
        }

    @Test fun manualEntryFromAnEditedReviewAsksThenReturnsToTheEditorWithTheFallbackNote() =
        solveScan { main, scan, started ->
            captureCube(scan)
            if (rule.onAllNodesWithTag("review:coach").fetchSemanticsNodes().isNotEmpty()) {
                rule.onNodeWithTag("review:coach").performClick()
            }
            val index = 1
            val other = Color.entries.first { it != cube.toFacelets().colors[index] }
            rule.onNodeWithTag("sticker:$index").performScrollTo().performClick()
            rule.onNodeWithTag("swatch:${other.name}").performScrollTo().performClick()
            rule.onNodeWithTag("review:enterColors").performScrollTo().performClick()
            rule.onNodeWithTag("review:discard:confirm").performClick()
            rule.waitForIdle()
            returnToSolve(main, scan, started, ScanOutcome.FellBackToEditor)
            rule.onNodeWithTag("solve:scanNote").assertTextEquals("No scan this time. Enter your cube's colors here.")
            rule.onNodeWithTag("editor:accept").assertExists()
        }

    @Test fun calibrationFromASolveReviewOffersManualEntryBackToTheEditor() =
        solveScan { main, scan, started ->
            captureCube(scan)
            val viewModel = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
            val review = viewModel.phase as GuidedScanPhase.Review
            rule.runOnIdle { viewModel.phase = GuidedScanPhase.Calibration(fromReview = review) }
            rule.waitForIdle()
            rule.onNodeWithTag("calibration:prompt").assertIsDisplayed()
            rule.onNodeWithTag("scanEnterColors").assertIsDisplayed().performClick()
            rule.waitForIdle()
            returnToSolve(main, scan, started, ScanOutcome.FellBackToEditor)
            rule.onNodeWithTag("solve:scanNote").assertTextEquals("No scan this time. Enter your cube's colors here.")
        }

    @Test fun fullQuickCheckKeepsManualEntryOutOfItsViewfinderAndReview() {
        val context = RuntimeEnvironment.getApplication()
        val intent = QuickCheckContract(launcher).createIntent(context, QuickCheckInput(Face.entries.toSet(), fullScan = true))
        withScan(intent) { scan ->
            rule.onNodeWithTag("scanCapture").assertIsDisplayed()
            rule.onNodeWithTag("scanEnterColors").assertDoesNotExist()
            captureCube(scan)
            rule.onNodeWithTag("review:accept").performScrollTo().assertIsEnabled()
            rule.onNodeWithTag("review:enterColors").assertDoesNotExist()
        }
    }

    @Test fun partialQuickCheckKeepsManualEntryOutOfItsViewfinder() {
        val intent =
            Intent(RuntimeEnvironment.getApplication(), GuidedScanActivity::class.java)
                .putExtra(QuickCheckContract.EXTRA_FACES, arrayOf(Face.U.name, Face.R.name))
        withScan(intent) {
            rule.onNodeWithTag("scanCapture").assertIsDisplayed()
            rule.onNodeWithTag("scanEnterColors").assertDoesNotExist()
        }
    }

    @Test fun settingsCalibrationKeepsManualEntryOutOfTheViewfinder() {
        val intent =
            Intent(RuntimeEnvironment.getApplication(), GuidedScanActivity::class.java)
                .putExtra(GuidedScanActivity.CALIBRATION_MODE, true)
        withScan(intent) {
            rule.onNodeWithTag("calibration:prompt").assertIsDisplayed()
            rule.onNodeWithTag("scanEnterColors").assertDoesNotExist()
        }
    }

    private fun captureCube(
        scan: ActivityController<GuidedScanActivity>,
        state: CubeState = cube,
    ) {
        val viewModel = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
        val colors = state.toFacelets().colors
        GuidedScanPlan.steps.forEach { step ->
            val start = step.face.ordinal * 9
            val cells = colors.subList(start, start + 9).map { CellReading(ScanRouteTest.labs.getValue(it), true) }
            camera.feed(FrameStats(cells, 120.0, 0.0, 100.0, 12), null)
            rule.waitForIdle()
            viewModel.frozen?.let { viewModel.endFreeze(it.id) }
            rule.waitForIdle()
        }
    }

    private fun solveScan(body: (MainActivity, ActivityController<GuidedScanActivity>, Intent) -> Unit) {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("welcome:imNew").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("welcome:imNew").performClick()
            rule.onNodeWithTag("nav:solve").performClick()
            rule.onNodeWithTag("solve:scan").performClick()
            val activity = shadowOf(main.get())
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                activity.peekNextStartedActivityForResult() != null
            }
            val started = activity.nextStartedActivityForResult.intent
            assertEquals(GuidedScanActivity::class.java.name, started.component?.className)
            withScan(started) { body(main.get(), it, started) }
        } finally {
            main.pause().stop().destroy()
        }
    }

    private fun withScan(
        intent: Intent,
        body: (ActivityController<GuidedScanActivity>) -> Unit,
    ) {
        val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, intent).setup()
        try {
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))
                camera.starts > 0
            }
            rule.waitForIdle()
            body(scan)
        } finally {
            if (scan.get().lifecycle.currentState != Lifecycle.State.DESTROYED) scan.pause().stop().destroy()
        }
    }

    private fun returnToSolve(
        main: MainActivity,
        scan: ActivityController<GuidedScanActivity>,
        started: Intent,
        expected: ScanOutcome,
    ) {
        val result = shadowOf(scan.get())
        assertEquals(expected, ScanFlowContract.decode(result.resultCode, result.resultIntent))
        shadowOf(main).receiveResult(started, result.resultCode, result.resultIntent)
        scan.pause().stop().destroy()
        rule.waitForIdle()
    }
}
