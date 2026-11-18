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
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import dev.cubentia.app.camera.CameraModule
import dev.cubentia.app.camera.GuidedScanActivity
import dev.cubentia.app.camera.ScanFrameSource
import dev.cubentia.app.di.SolverModule
import dev.cubentia.app.scan.ScanFlowContract
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.solver.SolverLoad
import dev.cubentia.app.solver.SolverSource
import dev.cubentia.app.ui.solve.SolvePhase
import dev.cubentia.domain.cube.CubeState
import org.junit.Assert.assertEquals
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

    @BindValue internal val frames: ScanFrameSource = ScanRouteTest.FakeFrames()

    private var denied = false

    @BindValue val launcher: ScanLauncher =
        object : ScanLauncher {
            override fun hasCamera(context: Context) = true

            override fun cameraDenied(context: Context) = denied

            override fun guidedScanIntent(context: Context) = Intent(context, GuidedScanActivity::class.java)
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
                .assertTextContains("Your cube is solved — scramble it and scan again, or start Stage 1.")
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
            val (code, data) = ScanFlowContract.encode(outcome)
            activity.receiveResult(started, code, data)
            rule.waitForIdle()
            body(main.get())
        } finally {
            main.pause().stop().destroy()
        }
    }
}
