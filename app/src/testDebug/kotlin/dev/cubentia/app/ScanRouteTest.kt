package dev.cubentia.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.camera.CameraModule
import dev.cubentia.app.camera.GuidedScanActivity
import dev.cubentia.app.camera.GuidedScanPhase
import dev.cubentia.app.camera.GuidedScanViewModel
import dev.cubentia.app.camera.ScanFrame
import dev.cubentia.app.camera.ScanFrameSource
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.app.ui.solve.SolveFlowViewModel
import dev.cubentia.app.ui.solve.SolvePhase
import dev.cubentia.application.port.CalibrationLoad
import dev.cubentia.application.port.CalibrationRepository
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.GuidedScanPlan
import dev.cubentia.domain.recognition.Lab
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.util.concurrent.ExecutorService
import javax.inject.Inject

@HiltAndroidTest
@UninstallModules(CameraModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp")
class ScanRouteTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Inject lateinit var repository: CalibrationRepository

    @Before fun setup() {
        hilt.inject()
        source = FakeFrames()
        denied = false
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA)
    }

    @Test fun scanCalibrationRescanReturnsCorrectedStickersToSolveEditor() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        rule.waitForIdle()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("welcome:imNew").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("welcome:imNew").performClick()
        rule.onNodeWithTag("nav:solve").performClick()
        rule.onNodeWithTag("solve:scan").performClick()
        rule.waitForIdle()
        rule.waitUntil(10_000) { shadowOf(main.get()).peekNextStartedActivityForResult() != null }
        val started = shadowOf(main.get()).nextStartedActivityForResult
        assertEquals(GuidedScanActivity::class.java.name, started.intent.component!!.className)
        val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, started.intent).setup()
        rule.waitForIdle()
        val vm = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
        rule.waitUntil(10_000) { source.starts > 0 }
        val cube =
            CubeState.SOLVED.apply(
                listOf(
                    FaceTurn(Face.R, Turn.CLOCKWISE),
                    FaceTurn(Face.U, Turn.COUNTERCLOCKWISE),
                    FaceTurn(Face.F, Turn.HALF),
                    FaceTurn(Face.L, Turn.CLOCKWISE),
                ),
            )

        fun frames(logo: Boolean) {
            GuidedScanPlan.steps.forEach { step ->
                val cells =
                    cube
                        .toFacelets()
                        .colors
                        .subList(step.face.ordinal * 9, step.face.ordinal * 9 + 9)
                        .mapIndexed { i, color ->
                            CellReading(
                                if (step.face == Face.F && i == 0) closeCall(color) else labs.getValue(color),
                                !(logo && step.centerColor == Color.WHITE && i == 4),
                            )
                        }
                source.feed(FrameStats(cells, 120.0, 0.0, 100.0, 12), null)
                rule.waitForIdle()
                vm.frozen?.let { vm.endFreeze(it.id) }
                rule.waitForIdle()
            }
        }
        frames(true)
        assertTrue(
            "Expected review, got ${vm.phase}; captured=${vm.controller.captured.map {
                it.face
            }}; step=${vm.controller.step}; coaching=${vm.controller.coaching}",
            vm.phase is GuidedScanPhase.Review,
        )
        val first = vm.phase as GuidedScanPhase.Review
        assertTrue(first.offerCalibration)
        assertTrue(first.review.blanks.isNotEmpty())
        val index = first.review.blanks.first()
        val color = cube.toFacelets().colors[index]
        // Paint via the real review controls, then enter calibration through its confirmation.
        if (rule.onAllNodesWithTag("review:coach").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithTag("review:coach").performClick()
        }
        rule.onNodeWithTag("sticker:$index").performClick()
        rule.onNodeWithTag("swatch:${color.name}").performClick()
        rule.onNodeWithTag("review:calibrate:offer").performClick()
        rule.onNodeWithTag("review:calibrate:confirm").performClick()
        rule.waitForIdle()
        val before = source.starts
        GuidedScanPlan.steps.forEach { step ->
            val lab = labs.getValue(step.centerColor)
            source.feed(FrameStats(List(9) { CellReading(lab, true) }, 120.0, 0.0, 100.0, 12), CellReading(lab, true))
            rule.waitForIdle()
            val calibration = vm.phase as GuidedScanPhase.Calibration
            assertNotNull(
                "Auto capture ${step.centerColor}: session=${calibration.session.current}, pending=${calibration.session.pending}, coaching=${calibration.coaching}, plain=${calibration.session.plainSticker}, starts=${source.starts}",
                calibration.session.pending,
            )
            rule.onNodeWithTag("calibration:keep").performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithTag("calibration:save").performClick()
        rule.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            !vm.saving
        }
        rule.waitForIdle()
        assertTrue("After save: ${vm.phase}", vm.phase is GuidedScanPhase.Viewfinder)
        assertEquals(labs, (runBlocking { repository.load() } as CalibrationLoad.Loaded).calibration?.centers)
        assertTrue(source.starts > before)
        frames(false)
        val review = vm.phase as GuidedScanPhase.Review
        assertEquals(color, review.review.stickers[index])
        assertTrue(review.edited)
        assertFalse(review.offerCalibration)
        rule.onNodeWithTag("review:accept").performClick()
        val result = shadowOf(scan.get())
        shadowOf(main.get()).receiveResult(started.intent, result.resultCode, result.resultIntent)
        scan.pause().stop().destroy()
        rule.waitForIdle()
        val editor = ViewModelProvider(main.get())[SolveFlowViewModel::class.java].phase.value as SolvePhase.Editing
        assertEquals(cube.toFacelets(), editor.initial)
        assertNull(editor.note)
        rule.onNodeWithTag("editor:accept").assertExists()
        main.pause().stop().destroy()
    }

    @Test fun recordedDenialOpensTheEditorWithTheNoteAndStartsNoScan() {
        denied = true
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        rule.waitForIdle()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("welcome:imNew").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("welcome:imNew").performClick()
        rule.onNodeWithTag("nav:solve").performClick()
        rule.onNodeWithTag("solve:scan").performClick()
        val flow = ViewModelProvider(main.get())[SolveFlowViewModel::class.java]
        rule.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            flow.phase.value is SolvePhase.Editing
        }
        rule.waitForIdle()
        assertNull(shadowOf(main.get()).peekNextStartedActivityForResult())
        rule.onNodeWithTag("solve:scanNote").assertTextEquals("Camera access is off, so enter your cube's colors here.")
        rule.onNodeWithTag("editor:accept").assertExists()
        main.pause().stop().destroy()
    }

    internal class FakeFrames : ScanFrameSource {
        var starts = 0
        private lateinit var executor: ExecutorService
        private var receiver: ((ScanFrame) -> Unit)? = null

        override fun preview(context: Context) = View(context)

        override fun start(
            owner: ComponentActivity,
            executor: ExecutorService,
            onFrame: (ScanFrame) -> Unit,
            onUnavailable: () -> Unit,
        ) {
            starts++
            this.executor = executor
            receiver = onFrame
        }

        override fun stop() {
            receiver = null
        }

        override fun meter() = Unit

        fun feed(
            stats: FrameStats,
            ring: CellReading?,
        ) {
            checkNotNull(receiver) { "Frame source must be running before delivery" }
            repeat(16) {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
                executor
                    .submit {
                        checkNotNull(
                            receiver,
                        ) { "Frame source stopped during delivery" }.invoke(ScanFrame(stats, 0) { ring })
                    }.get()
                shadowOf(android.os.Looper.getMainLooper()).idle()
            }
        }
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object CameraTestModule {
        @Provides internal fun frames(): ScanFrameSource = source

        @Provides fun launcher(): ScanLauncher =
            object : ScanLauncher {
                override fun hasCamera(context: Context) = true

                override fun cameraDenied(context: Context) = denied

                override fun guidedScanIntent(context: Context) = Intent(context, GuidedScanActivity::class.java)
            }
    }

    companion object {
        private lateinit var source: FakeFrames

        @Volatile private var denied = false

        private fun closeCall(color: Color): Lab {
            val a = labs.getValue(color)
            val b =
                labs
                    .filterKeys {
                        it != color
                    }.values
                    .minBy { (it.l - a.l) * (it.l - a.l) + (it.a - a.a) * (it.a - a.a) + (it.b - a.b) * (it.b - a.b) }
            return Lab(a.l * 0.51 + b.l * 0.49, a.a * 0.51 + b.a * 0.49, a.b * 0.51 + b.b * 0.49)
        }

        private val labs =
            mapOf(
                Color.GREEN to Lab(55.0, -60.0, 40.0),
                Color.RED to Lab(45.0, 65.0, 45.0),
                Color.BLUE to Lab(40.0, 10.0, -50.0),
                Color.ORANGE to Lab(65.0, 45.0, 65.0),
                Color.WHITE to Lab(90.0, 0.0, 0.0),
                Color.YELLOW to Lab(85.0, -5.0, 80.0),
            )
    }
}
