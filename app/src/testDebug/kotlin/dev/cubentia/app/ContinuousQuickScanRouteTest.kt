package dev.cubentia.app

import android.Manifest
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import dev.cubentia.app.camera.ContinuousFrame
import dev.cubentia.app.camera.ContinuousScanEngine
import dev.cubentia.app.camera.ContinuousScanEngineFactory
import dev.cubentia.app.camera.GuidedScanActivity
import dev.cubentia.app.camera.GuidedScanPhase
import dev.cubentia.app.camera.GuidedScanViewModel
import dev.cubentia.app.camera.ScanFrame
import dev.cubentia.app.camera.ScanFrameSource
import dev.cubentia.app.camera.ScanNetFaceCaptured
import dev.cubentia.app.camera.ShutterSound
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.continuous.ContinuousScanEngineModule
import dev.cubentia.app.camera.testHandoff
import dev.cubentia.app.camera.testSnapshot
import dev.cubentia.app.di.FlagsModule
import dev.cubentia.app.scan.ScanFlowContract
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.ui.solve.SolveFlowViewModel
import dev.cubentia.app.ui.solve.SolvePhase
import dev.cubentia.application.port.FeatureFlag
import dev.cubentia.application.port.FeatureFlags
import dev.cubentia.application.port.FlagLevel
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.QuickScanPlan
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.FaceSlot
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
@UninstallModules(CameraModule::class, ContinuousScanEngineModule::class, FlagsModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp")
class ContinuousQuickScanRouteTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val camera = FakeFrames()

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

    private var quickLevel = FlagLevel.BETA

    @BindValue val flags: FeatureFlags =
        object : FeatureFlags {
            override fun level(flag: FeatureFlag) = if (flag == FeatureFlag.QUICK_SCAN) quickLevel else FlagLevel.GA
        }

    private var exported: ContinuousScanExport = ContinuousScanExport.Partial(testHandoff(emptyList()))

    /** How many engines the factory made (one per scan, kept across a rotation) and how often one was reset. */
    private var enginesCreated = 0

    @Volatile private var engineResets = 0

    @BindValue internal val engines: ContinuousScanEngineFactory =
        ContinuousScanEngineFactory {
            enginesCreated++
            object : ContinuousScanEngine {
                override fun processFrame(
                    frame: YuvFrame,
                    rotationDegrees: Int,
                    viewWidth: Int,
                    viewHeight: Int,
                ) = error("camera supplies processed frames")

                override fun reset() {
                    assertTrue(Thread.currentThread() !== Looper.getMainLooper().thread)
                    engineResets++
                    exported = ContinuousScanExport.Partial(testHandoff(emptyList()))
                }

                override fun export(): ContinuousScanExport {
                    assertTrue(Thread.currentThread() !== Looper.getMainLooper().thread)
                    return exported
                }
            }
        }

    private fun show(
        faces: List<Face>,
        cue: ContinuousScanCue = ContinuousScanCue.TiltAdjacent,
        anchored: Boolean = true,
        blank: FaceSlot? = null,
        canComplete: Boolean = true,
    ) {
        val handoff = testHandoff(faces, anchored, blank)
        exported =
            if (cue == ContinuousScanCue.AllVerified && canComplete) {
                ContinuousScanExport.Complete(CubeState.SOLVED, CubeState.SOLVED.toFacelets(), handoff)
            } else {
                ContinuousScanExport.Partial(handoff)
            }
        camera.feed(ScanFrame(stats(Color.WHITE), 0, continuous = ContinuousFrame(testSnapshot(handoff, cue), emptyList())))
    }

    @Test fun completionOpensReviewAndAcceptReturnsToSolve() {
        quickScanActivity { scan, model ->
            show(Face.entries, ContinuousScanCue.AllVerified)
            advanceHold(model)
            rule.onNodeWithTag("review:retakeFaces").assertExists()
            rule.onNodeWithTag("review:accept").performScrollTo().performClick()
            assertEquals(ScanOutcome.Accepted(CubeState.SOLVED), outcome(scan.get()))
        }
    }

    @Test fun resetDuringHoldCancelsReviewAndPreservesTorch() {
        camera.flash = true
        quickScanActivity { _, model ->
            show(listOf(Face.U))
            rule.onNodeWithTag("quickScan:torch").assert(hasText("Torch off")).performClick()
            show(Face.entries, ContinuousScanCue.AllVerified)
            rule.onNodeWithTag("quickScan:cue").assertTextEquals("All 54 stickers verified.")
            rule.onNodeWithTag("quickScan:reset").performClick()
            advanceHold(model)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 0/6")
            rule.onNodeWithTag("quickScan:torch").assert(hasText("Torch on"))
            assertTrue(!model.analysisHeld)
        }
    }

    @Test fun partialExportAfterCompleteResumesFrames() {
        quickScanActivity { _, model ->
            show(Face.entries, ContinuousScanCue.AllVerified, canComplete = false)
            advanceHold(model)
            assertTrue(!model.analysisHeld)
            show(listOf(Face.U, Face.R))
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 2/6")
        }
    }

    @Test fun unanchoredEditorIsBlankAndUnanchoredGuidedAsksAllSix() {
        quickScanActivity { scan, model ->
            show(listOf(Face.U), anchored = false)
            rule.onNodeWithTag("quickScan:faceByFace").performClick()
            settle(model)
            rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 6")
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            waitForCamera(continuous = true)
            rule.onNodeWithTag("scanEnterColors").performClick()
            settle(model)
            assertEquals(ScanOutcome.FellBackToEditor(null), outcome(scan.get()))
        }
    }

    @Test fun twistOffersSeededEditorAndResetUnlocksScanning() {
        quickScanActivity { scan, model ->
            val faces = listOf(Face.U, Face.F, Face.R)
            show(faces, ContinuousScanCue.LayerTwistDetected)
            assertTrue(model.analysisHeld)
            rule.onNodeWithTag("quickScan:reset").performClick()
            settle(model)
            assertTrue(!model.analysisHeld)
            show(faces, ContinuousScanCue.LayerTwistDetected)
            rule.onNodeWithTag("scanEnterColors").performClick()
            settle(model)
            assertEquals(ScanOutcome.FellBackToEditor(testHandoff(faces).toFallbackStickers()), outcome(scan.get()))
        }
    }

    @Test fun guidedConflictAsksOnlyForUnreadFaceAndBackRestoresMapWithTorchOff() {
        camera.flash = true
        quickScanActivity { scan, model ->
            show(Face.entries, ContinuousScanCue.ResolveConflict(Face.F), blank = FaceSlot(Face.F, 2))
            rule.onNodeWithTag("quickScan:torch").performClick()
            rule.onNodeWithTag("quickScan:faceByFace").performClick()
            settle(model)
            rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 1")
            rule.onNodeWithText("Show the green center.").assertIsDisplayed()
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 6/6")
            rule.onNodeWithTag("quickScan:torch").assert(hasText("Torch off"))
            scan.get().onBackPressedDispatcher.onBackPressed()
            assertQuickScanDiscardCopy()
            rule.onNodeWithTag("scan:discard:cancel").performClick()
            rule.onNodeWithTag("scanClose").performClick()
            rule.onNodeWithTag("scan:discard:confirm").performClick()
            assertEquals(ScanOutcome.Cancelled, outcome(scan.get()))
        }
    }

    @Test fun backgroundAndRotationKeepMappedFacesAndTurnTorchOff() {
        camera.flash = true
        quickScanActivity { scan, model ->
            show(listOf(Face.U, Face.R))
            rule.onNodeWithTag("quickScan:torch").performClick()
            scan
                .pause()
                .stop()
                .restart()
                .start()
                .resume()
            settle(model)
            rule.onNodeWithTag("quickScan:torch").assert(hasText("Torch off"))
            scan.recreate()
            waitForCamera(continuous = true)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 2/6")
        }
    }

    @Test fun offHidesSolveButtonAndDirectIntentIsUnavailable() {
        quickLevel = FlagLevel.OFF
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("welcome:imNew").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("welcome:imNew").performClick()
            rule.onNodeWithTag("nav:solve").performClick()
            rule.onNodeWithTag("solve:quickScan").assertDoesNotExist()
            val intent = launcher.quickScanIntent(main.get())
            val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, intent).setup()
            try {
                rule.onNodeWithTag("scanUnavailable").assertIsDisplayed()
                rule.onNodeWithTag("quickScan:continuous").assertDoesNotExist()
            } finally {
                scan.pause().stop().destroy()
            }
        } finally {
            main.pause().stop().destroy()
        }
    }

    private fun advanceHold(model: GuidedScanViewModel) {
        settle(model)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1001))
        rule.mainClock.advanceTimeBy(1001)
        settle(model)
    }

    private fun outcome(scan: GuidedScanActivity): ScanOutcome {
        val shadow = shadowOf(scan)
        return ScanFlowContract.decode(shadow.resultCode, shadow.resultIntent)
    }

    @Test fun scanFaceByFaceAfterThreeMappedFacesAsksOnlyTheRemainingFacesAndAccepts() {
        quickScanActivity { scan, model ->
            rule.onNodeWithTag("quickScan:faceByFace").assertIsDisplayed()
            show(listOf(Face.U, Face.F, Face.R))
            rule.onNodeWithTag("quickScan:faceByFace").assertIsDisplayed().performClick()
            settle(model)
            rule.onNodeWithTag("quickScan:counter").assertDoesNotExist()
            rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 3")
            rule.onNodeWithText("Show the blue center.").assertIsDisplayed()
            // Close in the hand-over with kept faces asks first; Cancel keeps the hand-over exactly.
            rule.onNodeWithTag("scanClose").performClick()
            rule.onNodeWithTag("scan:discard").assertIsDisplayed()
            assertTrue(!scan.get().isFinishing)
            rule.onNodeWithTag("scan:discard:cancel").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("scan:discard").assertDoesNotExist()
            rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 3")
            listOf(Face.U, Face.F, Face.R).forEach {
                rule.onNodeWithTag("scanNetFace:${it.name}").assert(SemanticsMatcher.expectValue(ScanNetFaceCaptured, true))
            }
            waitForCamera(continuous = false)
            listOf(Color.BLUE, Color.ORANGE, Color.YELLOW).forEach { color ->
                camera.feed(ScanFrame(stats(color), 0))
                shutter(model)
            }
            // Retake faces runs from the combined Quick Scan review (Story 10.16) and returns to it.
            rule.waitUntil(10_000) { model.phase is GuidedScanPhase.Review }
            if (rule.onAllNodesWithTag("review:coach").fetchSemanticsNodes().isNotEmpty()) {
                rule.onNodeWithTag("review:coach").performClick()
            }
            rule.onNodeWithTag("review:retakeFaces").performScrollTo().performClick()
            rule.onNodeWithTag("reviewNet:face:${Face.F.ordinal}").performClick()
            rule.onNodeWithTag("review:retake:start").performScrollTo().performClick()
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                (model.phase as? GuidedScanPhase.Viewfinder)?.fromReview != null
            }
            rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 1")
            waitForCamera(continuous = false)
            camera.feed(ScanFrame(stats(Color.GREEN), 0))
            shutter(model)
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                model.phase is GuidedScanPhase.Review
            }
            rule.onNodeWithTag("review:accept").performScrollTo().performClick()
            val result = shadowOf(scan.get())
            assertEquals(ScanOutcome.Accepted(CubeState.SOLVED), ScanFlowContract.decode(result.resultCode, result.resultIntent))
        }
    }

    private fun assertQuickScanDiscardCopy() {
        rule.onNodeWithTag("scan:discard").assertIsDisplayed()
        rule.onNodeWithText("Discard this scan?").assertIsDisplayed()
        rule.onNodeWithText("Discard the faces you captured?").assertDoesNotExist()
        rule.onNodeWithTag("scan:discard:cancel").assertTextEquals("Cancel")
    }

    @Test fun closeWithNothingCapturedCancelsAtOnce() {
        quickScanActivity { scan, _ ->
            rule.onNodeWithTag("scanClose").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("scan:discard").assertDoesNotExist()
            assertTrue(scan.get().isFinishing)
            val result = shadowOf(scan.get())
            assertEquals(ScanOutcome.Cancelled, ScanFlowContract.decode(result.resultCode, result.resultIntent))
        }
    }

    @Test fun aCalibrationFromSettingsShowsTheTorchOnLightsItAndTagsTheSave() {
        camera.flash = true
        val intent = launcher.calibrationIntent(RuntimeEnvironment.getApplication())
        val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, intent).setup()
        try {
            waitForCamera(continuous = false)
            val model = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
            assertTrue(model.phase is GuidedScanPhase.Calibration)
            camera.feed(ScanFrame(stats(Color.WHITE), 0))
            settle(model)
            rule.onNodeWithTag("quickScan:torch").assertIsDisplayed().assert(hasText("Torch on"))
            assertEquals(true, camera.torchCalls.lastOrNull())
            rule.runOnIdle {
                val phase = model.phase as GuidedScanPhase.Calibration
                var session = phase.session
                while (session.current != null) {
                    session = session.offer(CellReading(ScanRouteTest.labs.getValue(session.current!!), true)).keep()
                }
                model.phase = phase.copy(session = session)
            }
            rule.onNodeWithTag("calibration:save").performScrollTo().performClick()
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                model.saved != null && !model.saving
            }
            assertEquals(true, model.saved?.torchOn)
        } finally {
            scan.pause().stop().destroy()
        }
    }

    @Test fun aCalibrationOpenedFromTheReviewStartsWithTheTorchOn() {
        camera.flash = true
        quickScanActivity { _, model ->
            show(listOf(Face.U))
            settle(model)
            rule.onNodeWithTag("quickScan:torch").assert(hasText("Torch off"))
            settle(model)
            assertEquals(false, model.torchOn)
            rule.runOnIdle {
                model.phase =
                    GuidedScanPhase.Review(
                        dev.cubentia.domain.recognition
                            .ScanReview(List(54) { null }, 0),
                        offerCalibration = true,
                    )
            }
            rule.onNodeWithTag("review:calibrate:offer").performScrollTo().performClick()
            rule.onNodeWithTag("review:calibrate:confirm").performClick()
            settle(model)
            assertTrue(model.phase is GuidedScanPhase.Calibration)
            assertEquals(true, model.torchOn)
            // Saving it restarts Quick Scan: an empty map, the engine reset, the torch off, and frames analysed again.
            rule.runOnIdle {
                val phase = model.phase as GuidedScanPhase.Calibration
                var session = phase.session
                while (session.current != null) {
                    session = session.offer(CellReading(ScanRouteTest.labs.getValue(session.current!!), true)).keep()
                }
                model.phase = phase.copy(session = session)
            }
            val resets = engineResets
            rule.onNodeWithTag("calibration:save").performScrollTo().performClick()
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                model.saved != null && !model.saving
            }
            settle(model)
            assertEquals(GuidedScanPhase.Continuous, model.phase)
            assertEquals(resets + 1, engineResets)
            assertEquals(1, enginesCreated)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 0/6")
            rule.onNodeWithTag("quickScan:torch").assert(hasText("Torch off"))
            assertEquals(false, model.torchOn)
            waitForCamera(continuous = true)
            assertEquals(false, camera.torchCalls.last())
            assertTrue(!model.analysisHeld && !model.busy)
            show(listOf(Face.U, Face.F))
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 2/6")
        }
    }

    @Test fun rotationKeepsTheTorchChoiceTheMapAndTheEngine() {
        camera.flash = true
        quickScanActivity { scan, model ->
            show(listOf(Face.U, Face.R))
            rule.onNodeWithTag("quickScan:torch").assert(hasText("Torch off")).performClick()
            settle(model)
            rule.onNodeWithTag("quickScan:torch").assert(hasText("Torch on"))
            val engine = model.engine
            scan.recreate()
            waitForCamera(continuous = true)
            settle(model)
            // The same scan session: its ViewModel, engine and map; and the Learner's torch choice, still lit.
            assertTrue(ViewModelProvider(scan.get())[GuidedScanViewModel::class.java] === model)
            assertTrue(model.engine === engine)
            assertEquals(1, enginesCreated)
            assertEquals(0, engineResets)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 2/6")
            rule.onNodeWithTag("quickScan:torch").assert(hasText("Torch on"))
            assertEquals(true, model.torchOn)
            assertEquals(true, camera.torchCalls.last())
        }
    }

    @Test fun enterColorsInTheHandOverWithNoGuidedCaptureReturnsTheKeptFaces() {
        val faces = listOf(Face.U, Face.F, Face.R)
        quickScanActivity { scan, model ->
            show(faces)
            rule.onNodeWithTag("quickScan:faceByFace").performClick()
            settle(model)
            rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 3")
            rule.onNodeWithTag("scanEnterColors").performClick()
            settle(model)
            assertEquals(ScanOutcome.FellBackToEditor(testHandoff(faces).toFallbackStickers()), outcome(scan.get()))
        }
    }

    @Test fun enterColorsInTheHandOverAfterAGuidedCaptureStillReturnsTheKeptFacesAndNeverAGuess() {
        val faces = listOf(Face.U, Face.F, Face.R)
        quickScanActivity { scan, model ->
            show(faces)
            rule.onNodeWithTag("quickScan:faceByFace").performClick()
            settle(model)
            waitForCamera(continuous = false)
            camera.feed(ScanFrame(stats(Color.BLUE), 0))
            shutter(model)
            rule.onNodeWithTag("scanProgress").assertTextEquals("Face 2 of 3")
            rule.onNodeWithTag("scanEnterColors").performClick()
            settle(model)
            val stickers = checkNotNull((outcome(scan.get()) as ScanOutcome.FellBackToEditor).stickers)
            val solved = CubeState.SOLVED.toFacelets().colors
            // Every kept sticker is there, and whatever else is filled is what was scanned: nothing is guessed.
            faces.forEach { face ->
                assertEquals(
                    solved.subList(face.ordinal * 9, face.ordinal * 9 + 9),
                    stickers.subList(face.ordinal * 9, face.ordinal * 9 + 9),
                )
            }
            stickers.forEachIndexed { index, color -> if (color != null) assertEquals(solved[index], color) }
            assertEquals(Color.BLUE, stickers[Face.B.ordinal * 9 + 4])
        }
    }

    @Test fun closingAnUnanchoredHandOverWithNoCaptureLeavesAtOnce() {
        quickScanActivity { scan, model ->
            show(listOf(Face.U), anchored = false)
            rule.onNodeWithTag("quickScan:faceByFace").performClick()
            settle(model)
            rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 6")
            rule.onNodeWithTag("scanClose").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("scan:discard").assertDoesNotExist()
            assertTrue(scan.get().isFinishing)
            assertEquals(ScanOutcome.Cancelled, outcome(scan.get()))
        }
    }

    @Test fun closingAnUnanchoredHandOverAfterACaptureAsksFirst() {
        quickScanActivity { scan, model ->
            show(listOf(Face.U), anchored = false)
            rule.onNodeWithTag("quickScan:faceByFace").performClick()
            settle(model)
            waitForCamera(continuous = false)
            camera.feed(ScanFrame(stats(Color.GREEN), 0))
            shutter(model)
            rule.onNodeWithTag("scanProgress").assertTextEquals("Face 2 of 6")
            rule.onNodeWithTag("scanClose").performClick()
            assertQuickScanDiscardCopy()
            assertTrue(!scan.get().isFinishing)
            rule.onNodeWithTag("scan:discard:confirm").performClick()
            assertEquals(ScanOutcome.Cancelled, outcome(scan.get()))
        }
    }

    /** Opens Quick Scan from the Solve tab (`solve:quickScan`) and runs [body] on its activity once the continuous camera runs. */
    private fun quickScanActivity(
        body: (org.robolectric.android.controller.ActivityController<GuidedScanActivity>, GuidedScanViewModel) -> Unit,
    ) {
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
            val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, intent).setup()
            try {
                waitForCamera(continuous = true)
                body(scan, ViewModelProvider(scan.get())[GuidedScanViewModel::class.java])
                if (scan.get().isFinishing) {
                    val result = shadowOf(scan.get())
                    shadow.receiveResult(intent, result.resultCode, result.resultIntent)
                    rule.waitForIdle()
                    val flow = ViewModelProvider(main.get())[SolveFlowViewModel::class.java]
                    when (val decoded = ScanFlowContract.decode(result.resultCode, result.resultIntent)) {
                        is ScanOutcome.Accepted -> {
                            assertEquals(SolvePhase.AlreadySolved, flow.phase.value)
                        }

                        is ScanOutcome.FellBackToEditor -> {
                            assertEquals(
                                decoded.stickers != null,
                                (flow.phase.value as SolvePhase.Editing).seeded,
                            )
                        }

                        ScanOutcome.Cancelled -> {
                            assertEquals(SolvePhase.Home, flow.phase.value)
                        }
                    }
                }
            } finally {
                scan.pause().stop().destroy()
            }
        } finally {
            main.pause().stop().destroy()
        }
    }

    /** Lets the analyzer thread and the main looper run what a tap or Back started. */
    private fun settle(model: GuidedScanViewModel) {
        model.analyzerExecutor.submit {}.get()
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
        model.analyzerExecutor.submit {}.get()
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
    }

    private fun shutter(model: GuidedScanViewModel) {
        rule.onNodeWithTag("scanCapture").performClick()
        model.analyzerExecutor.submit {}.get()
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        rule.runOnIdle { model.frozen?.let { model.endFreeze(it.id) } }
    }

    private fun waitForCamera(continuous: Boolean) {
        rule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            camera.receiver != null && camera.continuous == continuous
        }
    }

    private fun stats(color: Color) = FrameStats(List(9) { CellReading(ScanRouteTest.labs.getValue(color), true) }, 120.0, 0.0, 100.0, 12)

    private class FakeFrames : ScanFrameSource {
        @Volatile var receiver: ((ScanFrame) -> Unit)? = null

        @Volatile var continuous = false
        val meterCalls = mutableListOf<Boolean>()
        private lateinit var executor: ExecutorService

        override fun preview(context: Context) = android.view.View(context)

        override fun continuousMode(
            engine: ContinuousScanEngine?,
            analyzing: () -> Boolean,
        ) {
            continuous = engine != null
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

        override fun meter() {
            meterCalls += true
        }

        override fun meter(includeAwb: Boolean) {
            meterCalls += includeAwb
        }

        /** Story 10.19: whether the fake camera has a flash unit (null: unknown, as before a camera is bound). */
        @Volatile var flash: Boolean? = null

        val torchCalls = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val suspendCalls = java.util.concurrent.CopyOnWriteArrayList<Boolean>()

        override fun hasFlashUnit(): Boolean? = flash

        override fun torch(on: Boolean) {
            torchCalls += on
        }

        override fun suspendTorch(suspended: Boolean) {
            suspendCalls += suspended
        }

        /** The torch applies at once: the last state the activity asked for. */
        override fun torchApplied(): Boolean? = if (flash == true) torchCalls.lastOrNull() ?: false else null

        fun feed(frame: ScanFrame) {
            executor.submit { checkNotNull(receiver)(frame) }.get()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
