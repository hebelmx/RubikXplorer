package dev.cubentia.app

import android.Manifest
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import dev.cubentia.app.camera.CameraModule
import dev.cubentia.app.camera.CameraScanLauncher
import dev.cubentia.app.camera.CornerFrame
import dev.cubentia.app.camera.GuidedScanActivity
import dev.cubentia.app.camera.GuidedScanViewModel
import dev.cubentia.app.camera.ScanFrame
import dev.cubentia.app.camera.ScanFrameSource
import dev.cubentia.app.scan.ScanFlowContract
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.ui.solve.SolveFlowViewModel
import dev.cubentia.app.ui.solve.SolvePhase
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.QuickScanPlan
import org.junit.Assert.assertEquals
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
import java.time.Duration
import java.util.concurrent.ExecutorService

@HiltAndroidTest
@UninstallModules(CameraModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp")
class QuickScanRouteTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val camera = FakeCornerFrames()

    @BindValue internal val frames: ScanFrameSource = camera

    @BindValue val launcher: ScanLauncher =
        object : ScanLauncher by CameraScanLauncher() {
            override fun hasCamera(context: Context) = true
        }

    @Before
    fun setup() {
        hilt.inject()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA)
    }

    @Test fun acceptsTwoCornerHoldsFromSolve() = scan(uncertain = false)

    @Test fun onlyTheUncertainFaceUsesTheGuidedGrid() = scan(uncertain = true)

    private fun scan(uncertain: Boolean) {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("welcome:imNew").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("welcome:imNew").performClick()
            rule.onNodeWithTag("nav:solve").performClick()
            rule.onNodeWithTag("solve:quickScan").assertContentDescriptionEquals("Quick Scan, BETA").performClick()
            val shadow = shadowOf(main.get())
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                shadow.peekNextStartedActivityForResult() != null
            }
            val intent = shadow.nextStartedActivityForResult.intent
            assertEquals(GuidedScanActivity::class.java.name, intent.component?.className)
            assertTrue(intent.getBooleanExtra(GuidedScanActivity.EXTRA_QUICK_SCAN, false))
            val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, intent).setup()
            try {
                waitForCamera(corner = true)
                val model = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
                rule.onNodeWithTag("quickScan:counter").assertTextEquals("1 / 2")
                captureHold(1, uncertain, model)
                rule.onNodeWithTag("quickScan:counter").assertTextEquals("2 / 2")
                captureHold(2, false, model)
                if (uncertain) {
                    rule.onNodeWithTag("quickScan:counter").assertDoesNotExist()
                    rule.onNodeWithTag("scanProgress").assertTextEquals("Again: face 1 of 1")
                    waitForCamera(corner = false)
                    camera.feed(ScanFrame(stats(Color.GREEN), 0))
                    shutter(model)
                }
                rule.onNodeWithTag("review:accept").performScrollTo().performClick()
                val result = shadowOf(scan.get())
                assertEquals(ScanOutcome.Accepted(CubeState.SOLVED), ScanFlowContract.decode(result.resultCode, result.resultIntent))
                shadow.receiveResult(intent, result.resultCode, result.resultIntent)
                rule.waitForIdle()
                val flow = ViewModelProvider(main.get())[SolveFlowViewModel::class.java]
                assertEquals(CubeState.SOLVED.toFacelets(), (flow.phase.value as SolvePhase.Editing).initial)
            } finally {
                scan.pause().stop().destroy()
            }
        } finally {
            main.pause().stop().destroy()
        }
    }

    @Test fun steadyCornersCaptureThemselvesAndTheRunRecordCountsReviewEdits() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("welcome:imNew").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("welcome:imNew").performClick()
            rule.onNodeWithTag("nav:solve").performClick()
            rule.onNodeWithTag("solve:quickScan").performClick()
            val shadow = shadowOf(main.get())
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                shadow.peekNextStartedActivityForResult() != null
            }
            val intent = shadow.nextStartedActivityForResult.intent
            val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, intent).setup()
            try {
                waitForCamera(corner = true)
                val model = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
                rule.onNodeWithTag("quickScan:counter").assertTextEquals("1 / 2")
                holdSteady(1, model)
                rule.onNodeWithTag("quickScan:counter").assertTextEquals("2 / 2")
                holdSteady(2, model)
                rule.waitForIdle()
                assertTrue("Expected review, got ${model.phase}", model.phase is dev.cubentia.app.camera.GuidedScanPhase.Review)
                ShadowLog.clear()
                if (rule.onAllNodesWithTag("review:coach").fetchSemanticsNodes().isNotEmpty()) {
                    rule.onNodeWithTag("review:coach").performClick()
                }
                rule.onNodeWithTag("sticker:0").performClick()
                rule.onNodeWithTag("swatch:${Color.RED.name}").performClick()
                rule.onNodeWithTag("review:close").performScrollTo().performClick()
                rule.onNodeWithTag("review:discard:confirm").performClick()
                rule.waitForIdle()
                val runs = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }.filter { it.startsWith("quickScan run ") }
                assertEquals(1, runs.size)
                assertTrue(runs.single(), runs.single().contains("fallbackFaces=0") && runs.single().contains("corrections=1"))
                assertTrue(runs.single(), runs.single().endsWith("outcome=CANCELLED"))
            } finally {
                scan.pause().stop().destroy()
            }
        } finally {
            main.pause().stop().destroy()
        }
    }

    /** Feeds one hold's corner frames over time with no shutter tap: remeter, settle, then 600 ms steady. */
    private fun holdSteady(
        hold: Int,
        model: GuidedScanViewModel,
    ) {
        val faces = QuickScanPlan.holds[hold - 1].map { step -> stats(step.centerColor) }
        val frame = ScanFrame(faces.first(), 0, corner = CornerFrame(true, faces))
        camera.feed(frame)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        camera.feed(frame)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(650))
        camera.feed(frame)
        rule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        rule.runOnIdle { model.frozen?.let { model.endFreeze(it.id) } }
    }

    private fun captureHold(
        hold: Int,
        uncertain: Boolean,
        model: GuidedScanViewModel,
    ) {
        val faces = QuickScanPlan.holds[hold - 1].map { step -> stats(step.centerColor, uncertain && step.centerColor == Color.GREEN) }
        camera.feed(ScanFrame(faces.first(), 0, corner = CornerFrame(true, faces)))
        shutter(model)
    }

    private fun shutter(model: GuidedScanViewModel) {
        rule.onNodeWithTag("scanCapture").performClick()
        model.analyzerExecutor.submit {}.get()
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        rule.runOnIdle { model.frozen?.let { model.endFreeze(it.id) } }
    }

    private fun waitForCamera(corner: Boolean) {
        rule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            camera.receiver != null && camera.corner == corner
        }
    }

    private fun stats(
        color: Color,
        uncertain: Boolean = false,
    ): FrameStats =
        FrameStats(
            List(9) { index ->
                CellReading(
                    if (uncertain &&
                        index == 0
                    ) {
                        Lab(55.0, 55.0, 55.0)
                    } else {
                        ScanRouteTest.labs.getValue(color)
                    },
                    true,
                )
            },
            120.0,
            0.0,
            100.0,
            12,
        )

    private class FakeCornerFrames : ScanFrameSource {
        @Volatile var receiver: ((ScanFrame) -> Unit)? = null

        @Volatile var corner = false
        private lateinit var executor: ExecutorService

        override fun preview(context: Context) = android.view.View(context)

        override fun cornerMode(enabled: Boolean) {
            corner = enabled
        }

        override fun start(
            owner: ComponentActivity,
            executor: ExecutorService,
            onFrame: (ScanFrame) -> Unit,
            onUnavailable: () -> Unit,
        ) {
            this.executor = executor
            receiver = onFrame
        }

        override fun stop() {
            receiver = null
        }

        override fun meter() = Unit

        fun feed(frame: ScanFrame) {
            executor.submit { checkNotNull(receiver)(frame) }.get()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
