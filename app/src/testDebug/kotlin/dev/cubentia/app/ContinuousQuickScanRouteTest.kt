package dev.cubentia.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
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
import dev.cubentia.app.camera.ContinuousFrameTap
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
import dev.cubentia.app.camera.session.QuickScanSessionController
import dev.cubentia.app.camera.session.SessionPhase
import dev.cubentia.app.camera.testHandoff
import dev.cubentia.app.camera.testSnapshot
import dev.cubentia.app.di.FlagsModule
import dev.cubentia.app.scan.RunScanColorsExtra
import dev.cubentia.app.scan.ScanFlowContract
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.ui.solve.SolveFlowViewModel
import dev.cubentia.app.ui.solve.SolvePhase
import dev.cubentia.application.port.CalibrationLoad
import dev.cubentia.application.port.CalibrationRepository
import dev.cubentia.application.port.FeatureFlag
import dev.cubentia.application.port.FeatureFlags
import dev.cubentia.application.port.FlagLevel
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.QuickScanPlan
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.FaceSlot
import kotlinx.coroutines.runBlocking
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
import java.time.Duration
import java.util.concurrent.ExecutorService
import javax.inject.Inject

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

    @Inject lateinit var repository: CalibrationRepository

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

    @Volatile private var exports = 0

    @Volatile private var unavailable = false

    @Volatile private var engineResets = 0

    /**
     * When set, the engine's export waits on the analyzer thread until it opens: an export the analyzer finishes before
     * main suspends on it completes inside the tap, so a test that needs one in flight holds it here.
     */
    @Volatile private var exportGate: java.util.concurrent.CountDownLatch? = null

    @BindValue internal val engines: ContinuousScanEngineFactory =
        ContinuousScanEngineFactory {
            enginesCreated++
            object : ContinuousScanEngine {
                override val detectorUnavailable get() = unavailable

                override fun processFrame(
                    frame: YuvFrame,
                    rotationDegrees: Int,
                    viewWidth: Int,
                    viewHeight: Int,
                    savedCalibration: CalibrationCenters?,
                    torchLit: Boolean,
                ) = error("camera supplies processed frames")

                /** Story 10.34: the recording (or baseline timing) tap, as the real engine keeps it until the next reset. */
                private var tap: ContinuousFrameTap? = null

                override fun reset() {
                    assertTrue(Thread.currentThread() !== Looper.getMainLooper().thread)
                    engineResets++
                    tap = null
                    exported = ContinuousScanExport.Partial(testHandoff(emptyList()))
                }

                override fun record(tap: ContinuousFrameTap?) {
                    reset()
                    this.tap = tap
                }

                override fun frameDone(
                    sensorTimestampNs: Long,
                    analyzerNs: Long,
                    rotation: Int,
                    imageWidth: Int,
                    imageHeight: Int,
                    cropLeft: Int,
                    cropTop: Int,
                    cropRight: Int,
                    cropBottom: Int,
                ) {
                    tap?.frameDone(
                        sensorTimestampNs,
                        analyzerNs,
                        rotation,
                        imageWidth,
                        imageHeight,
                        cropLeft,
                        cropTop,
                        cropRight,
                        cropBottom,
                    )
                }

                /** GH #406: a fixed per-stage timing body, as the debug pipeline engine reports at finish. */
                override fun timingRunLine(): String = TIMING_BODY

                override fun export(): ContinuousScanExport {
                    assertTrue(Thread.currentThread() !== Looper.getMainLooper().thread)
                    exports++
                    exportGate?.await()
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
        blanks: Set<FaceSlot> = setOfNotNull(blank),
        conflicts: List<FaceSlot> = emptyList(),
        centresOnly: Boolean = false,
    ) {
        val handoff = testHandoff(faces, anchored, blanks = blanks, centresOnly = centresOnly)
        exported =
            if (cue == ContinuousScanCue.AllVerified && canComplete) {
                ContinuousScanExport.Complete(CubeState.SOLVED, CubeState.SOLVED.toFacelets(), handoff)
            } else {
                ContinuousScanExport.Partial(handoff)
            }
        camera.feed(ScanFrame(stats(Color.WHITE), 0, continuous = ContinuousFrame(testSnapshot(handoff, cue, conflicts), emptyList())))
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

    @Test fun finishingLogsTheTimingRunLineOnceImmediatelyBeforeTheRunLine() {
        quickScanActivity { scan, model ->
            show(Face.entries, ContinuousScanCue.AllVerified)
            advanceHold(model)
            rule.onNodeWithTag("review:accept").performScrollTo().performClick()
            assertEquals(ScanOutcome.Accepted(CubeState.SOLVED), outcome(scan.get()))
            // GH #406: the engine's timing run line is logged once, directly before the run line it belongs to.
            val logged = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }
            val timing = logged.filter { it.startsWith("quickScan timing run ") }
            assertEquals(logged.toString(), 1, timing.size)
            assertTrue(
                timing.single(),
                Regex("quickScan timing run ${Regex.escape(TIMING_BODY)} device=[A-Za-z0-9-]+").matches(timing.single()),
            )
            val at = logged.indexOf(timing.single())
            assertTrue(logged.toString(), logged.getOrNull(at + 1)?.startsWith("quickScan run ") == true)
            assertEquals(1, quickScanRuns().size)
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

    /**
     * GH #390: the detector latches unavailable and the camera delivers the frame it failed on; the snapshot it carries is
     * an empty one, so a test sees at once if the card's route ever published it over the shown map.
     */
    private fun feedUnavailable(beforeMainHandlesFrame: () -> Unit = {}) {
        unavailable = true
        val frame = ContinuousFrame(testSnapshot(testHandoff(emptyList())), emptyList())
        camera.feed(ScanFrame(stats(Color.WHITE), 0, continuous = frame), beforeMainHandlesFrame)
    }

    private val unavailableMessage = "Quick Scan isn't available right now. You can scan face by face or enter your cube's colors by hand."

    private fun waitForUnavailableCard() {
        rule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.onAllNodesWithTag("quickScanUnavailable").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The card alone: its exact text, its two actions in order, *Close* above, and nothing of the live scan. */
    private fun assertUnavailableCard(model: GuidedScanViewModel) {
        assertEquals(GuidedScanPhase.QuickScanUnavailable, model.phase)
        rule.onNodeWithTag("quickScanUnavailable").assertIsDisplayed()
        rule.onNodeWithText(unavailableMessage).assertIsDisplayed()
        val actions = rule.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("quickScanUnavailable")))
        actions.assertCountEquals(2)
        actions[0].assertTextEquals("Scan face by face")
        actions[1].assertTextEquals("Enter colors instead")
        rule.onNodeWithTag("scanClose").assertIsDisplayed()
        for (tag in listOf("continuous", "count", "cue", "net", "announcement", "reset", "faceByFace", "torch", "reviewAndCorrect")) {
            rule.onNodeWithTag("quickScan:$tag").assertDoesNotExist()
        }
        rule.onNodeWithTag("scanViewfinder").assertDoesNotExist()
        rule.onNodeWithText("Bring the cube into view.").assertDoesNotExist()
        // No torch and no analysis while the card shows.
        assertEquals(false, model.torchOn)
        assertTrue(camera.receiver == null)
    }

    @Test fun unavailableBeforeMappingShowsTheCardAndCloseCancelsAtOnce() {
        quickScanActivity { scan, model ->
            rule.onNodeWithTag("quickScan:cue").assertTextEquals("Bring the cube into view.")
            feedUnavailable()
            settle(model)
            assertUnavailableCard(model)
            rule.onNodeWithTag("scanClose").performClick()
            rule.onNodeWithTag("scan:discard").assertDoesNotExist()
            assertTrue(scan.get().isFinishing)
            assertEquals(ScanOutcome.Cancelled, outcome(scan.get()))
        }
    }

    @Test fun unavailableBeforeMappingBackCancelsAtOnce() {
        quickScanActivity { scan, model ->
            feedUnavailable()
            settle(model)
            assertUnavailableCard(model)
            scan.get().onBackPressedDispatcher.onBackPressed()
            rule.onNodeWithTag("scan:discard").assertDoesNotExist()
            assertTrue(scan.get().isFinishing)
            assertEquals(ScanOutcome.Cancelled, outcome(scan.get()))
        }
    }

    @Test fun unavailableBeforeMappingEnterColorsOpensAnEmptyEditor() {
        quickScanActivity { scan, model ->
            feedUnavailable()
            settle(model)
            assertUnavailableCard(model)
            rule.onNodeWithTag("scanEnterColors").performClick()
            settle(model)
            assertEquals(1, exports)
            assertEquals(ScanOutcome.FellBackToEditor(null), outcome(scan.get()))
        }
    }

    @Test fun unavailableWithMappedSidesTurnsOffTorchAndConfirmsCloseAndBack() {
        camera.flash = true
        quickScanActivity { scan, model ->
            showThreeFullyReadFaces()
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 6/6")
            rule.onNodeWithTag("quickScan:torch").performClick()
            settle(model)
            assertEquals(true, model.torchOn)
            assertEquals(true, camera.torchCalls.last())
            val before = model.continuous
            feedUnavailable()
            settle(model)
            assertUnavailableCard(model)
            // The map the card's actions work from is the one the live scan showed, and the camera is told torch off.
            assertEquals(before, model.continuous)
            assertEquals(false, camera.torchCalls.last())
            assertEquals(0, engineResets)
            rule.onNodeWithTag("scanClose").performClick()
            assertQuickScanDiscardCopy()
            assertTrue(!scan.get().isFinishing)
            rule.onNodeWithTag("scan:discard:cancel").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("scan:discard").assertDoesNotExist()
            assertUnavailableCard(model)
            scan.get().onBackPressedDispatcher.onBackPressed()
            assertQuickScanDiscardCopy()
            rule.onNodeWithTag("scan:discard:confirm").performClick()
            assertTrue(scan.get().isFinishing)
            assertEquals(ScanOutcome.Cancelled, outcome(scan.get()))
        }
    }

    @Test fun scanFaceByFaceOnTheCardAsksForTheSameFacesAsTheLiveButtonAndBackReturnsToTheCardWithTheMapIntact() {
        quickScanActivity { scan, model ->
            showThreeFullyReadFaces()
            val before = model.continuous
            val stickers = exported.handoff.toFallbackStickers()
            // What the live screen's button asks for, to compare the card's with.
            rule.onNodeWithTag("quickScan:faceByFace").performClick()
            settle(model)
            val liveHandOver = model.phase
            val liveKept = model.handoff
            assertTrue((liveHandOver as GuidedScanPhase.Viewfinder).handOver)
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            waitForCamera(continuous = true)
            assertEquals(before, model.continuous)

            feedUnavailable()
            settle(model)
            assertUnavailableCard(model)
            val exportsBefore = exports
            repeat(2) { round ->
                rule.onNodeWithText("Scan face by face").performClick()
                settle(model)
                // A fresh export each time, and the same hand-over as the live button's: only B, L and D are asked for.
                assertEquals(exportsBefore + round + 1, exports)
                rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 3")
                rule.onNodeWithText("Show the blue center.").assertIsDisplayed()
                assertEquals(liveHandOver, model.phase)
                assertEquals(liveKept, model.handoff)
                assertEquals(setOf(Face.U, Face.F, Face.R), model.handoff?.faces?.keys)
                // Back returns to the card, never to the live scan, with the map intact.
                scan.get().onBackPressedDispatcher.onBackPressed()
                settle(model)
                assertUnavailableCard(model)
                assertEquals(before, model.continuous)
                assertEquals(null, model.handoff)
            }
            assertEquals(0, engineResets)
            rule.onNodeWithTag("scanEnterColors").performClick()
            settle(model)
            assertEquals(exportsBefore + 3, exports)
            assertEquals(ScanOutcome.FellBackToEditor(stickers), outcome(scan.get()))
        }
    }

    @Test fun reopeningWithTheDetectorUnavailableShowsTheCardAtOnceAndNeverStartsTheLiveScan() {
        // The detector latched in an earlier scan of this app process: the new engine reports it before any frame.
        unavailable = true
        quickScanActivity(ready = ::waitForUnavailableCard) { scan, model ->
            settle(model)
            assertUnavailableCard(model)
            // No frame was fed: the live scan was never composed (it alone makes the preview) and no analysis started.
            assertEquals(0, camera.previews)
            assertEquals(0, camera.starts)
            assertTrue(!camera.continuous)
            assertEquals(1, enginesCreated)
            rule.onNodeWithTag("scanEnterColors").performClick()
            settle(model)
            assertEquals(1, exports)
            assertEquals(ScanOutcome.FellBackToEditor(null), outcome(scan.get()))
        }
    }

    @Suppress("DEPRECATION") // The system's permission result, delivered as the platform does.
    @Test
    fun aCameraGrantWhileTheDetectorIsUnavailableOpensTheCard() {
        unavailable = true
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, launcher.quickScanIntent(app)).setup()
        try {
            val model = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                model.calibrationLoaded && rule.onAllNodesWithTag("scanRationale").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Continue").performClick()
            val request = shadowOf(scan.get()).lastRequestedPermission
            assertEquals(listOf(Manifest.permission.CAMERA), request.requestedPermissions.toList())
            shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
            scan.get().onRequestPermissionsResult(
                request.requestCode,
                request.requestedPermissions,
                intArrayOf(PackageManager.PERMISSION_GRANTED),
            )
            settle(model)
            assertUnavailableCard(model)
            assertEquals(0, camera.previews)
            assertEquals(0, camera.starts)
        } finally {
            scan.pause().stop().destroy()
        }
    }

    @Test fun theCameraReadsTheRepositoryCalibrationAfterLoadAndTheNewOneAfterAMidScreenSave() {
        // Story 10.37 E9: the supplier the activity registers returns only the persisted calibration, read live.
        val stored = CalibrationCenters(Color.entries.associateWith { Lab(40.0 + it.ordinal, it.ordinal * 7.0, -it.ordinal * 3.0) })
        runBlocking { repository.save(stored) }
        quickScanActivity { _, model ->
            settle(model)
            assertEquals(stored, (runBlocking { repository.load() } as CalibrationLoad.Loaded).calibration)
            assertEquals(stored, camera.savedCalibration())
            show(listOf(Face.U))
            settle(model)
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
                model.saved != stored && !model.saving
            }
            settle(model)
            val saved = checkNotNull(model.saved)
            assertEquals(ScanRouteTest.labs, saved.centers)
            assertEquals(saved, camera.savedCalibration())
        }
    }

    @Test fun theCameraReadsNoCalibrationWhenOnlyRunScanColoursArePresent() {
        // Story 10.37 E9: the run's start-scan colours never reach the continuous mapper.
        val app = RuntimeEnvironment.getApplication()
        val colors = CalibrationCenters(ScanRouteTest.labs)
        val intent = RunScanColorsExtra.put(launcher.quickScanIntent(app), colors)
        val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, intent).setup()
        try {
            val model = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
            waitForCamera(continuous = true)
            assertEquals(colors, model.runScan)
            assertNull(model.saved)
            assertNull(camera.savedCalibration())
        } finally {
            scan.pause().stop().destroy()
        }
    }

    @Test fun aCalibrationSavedFromAReviewWhileTheDetectorIsUnavailableReturnsToTheCard() {
        quickScanActivity { _, model ->
            show(listOf(Face.U))
            settle(model)
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
            rule.runOnIdle {
                val phase = model.phase as GuidedScanPhase.Calibration
                var session = phase.session
                while (session.current != null) {
                    session = session.offer(CellReading(ScanRouteTest.labs.getValue(session.current!!), true)).keep()
                }
                model.phase = phase.copy(session = session)
            }
            unavailable = true
            rule.onNodeWithTag("calibration:save").performScrollTo().performClick()
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                model.saved != null && !model.saving
            }
            settle(model)
            assertUnavailableCard(model)
            assertEquals(1, enginesCreated)
        }
    }

    @Test fun unavailableNeverReplacesAnOpenDiscardQuestionAndShowsOnTheNextFrameAfterCancel() {
        quickScanActivity { scan, model ->
            showThreeFullyReadFaces()
            val before = model.continuous
            // The question opens after the analyzer saw the unavailable frame and before the main thread handles it.
            feedUnavailable { scan.get().onBackPressedDispatcher.onBackPressed() }
            settle(model)
            assertQuickScanDiscardCopy()
            assertEquals(GuidedScanPhase.Continuous, model.phase)
            rule.onNodeWithTag("quickScanUnavailable").assertDoesNotExist()
            // With the question open no frame is handled at all.
            feedUnavailable()
            settle(model)
            assertQuickScanDiscardCopy()
            assertEquals(GuidedScanPhase.Continuous, model.phase)
            rule.onNodeWithTag("quickScanUnavailable").assertDoesNotExist()
            rule.onNodeWithTag("scan:discard:cancel").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("scan:discard").assertDoesNotExist()
            rule.onNodeWithTag("quickScan:continuous").assertIsDisplayed()
            feedUnavailable()
            settle(model)
            assertUnavailableCard(model)
            assertEquals(before, model.continuous)
        }
    }

    @Test fun reviewAndCorrectAndCompletionNeverRunFromTheCard() {
        quickScanActivity { _, model ->
            showStalled()
            assertTrue(model.continuous.reviewAndCorrectOffered())
            feedUnavailable()
            settle(model)
            assertUnavailableCard(model)
            val exportsBefore = exports
            // A tap left over from the live scan, landing after the card replaced it.
            model.reviewAndCorrect()
            assertTrue(!model.busy)
            settle(model)
            assertEquals(exportsBefore, exports)
            assertUnavailableCard(model)
            model.completeContinuous()
            assertTrue(!model.busy)
            settle(model)
            assertEquals(exportsBefore, exports)
            assertUnavailableCard(model)
        }
    }

    @Test fun rotationKeepsTheCardTheMapAndTheEngineAndStartsNoCamera() {
        quickScanActivity { scan, model ->
            showThreeFullyReadFaces()
            val before = model.continuous
            feedUnavailable()
            settle(model)
            assertUnavailableCard(model)
            val engine = model.engine
            val starts = camera.starts
            val previews = camera.previews
            scan.recreate()
            waitForUnavailableCard()
            settle(model)
            assertTrue(ViewModelProvider(scan.get())[GuidedScanViewModel::class.java] === model)
            assertUnavailableCard(model)
            assertTrue(model.engine === engine)
            assertEquals(1, enginesCreated)
            assertEquals(0, engineResets)
            assertEquals(before, model.continuous)
            assertEquals(starts, camera.starts)
            assertEquals(previews, camera.previews)
        }
    }

    @Test fun scanFaceByFaceOnTheCardOfAReopenedQuickScanStartsTheGuidedCameraAndBackReturnsToTheCard() {
        unavailable = true
        quickScanActivity(ready = ::waitForUnavailableCard) { scan, model ->
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                model.calibrationLoaded
            }
            settle(model)
            assertUnavailableCard(model)
            assertEquals(0, camera.starts)
            rule.onNodeWithText("Scan face by face").performClick()
            settle(model)
            // Nothing was mapped, so Guided asks for all six faces, on a camera that starts here for the first time.
            rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 6")
            waitForCamera(continuous = false)
            assertTrue(camera.starts > 0)
            assertTrue(model.handoff?.faces?.isEmpty() == true)
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            assertUnavailableCard(model)
            assertEquals(1, enginesCreated)
        }
    }

    @Test fun anUnavailableFrameCannotInterruptAnExportAndBackFromEarlyReviewReturnsToTheCard() {
        quickScanActivity { scan, model ->
            showStalled()
            val before = model.continuous
            // *Review and Correct* starts its export after the analyzer saw the frame and before main handles it; the export
            // is held on the analyzer until main has handled the frame, so it is still in flight then.
            val gate = java.util.concurrent.CountDownLatch(1)
            exportGate = gate
            try {
                feedUnavailable {
                    model.reviewAndCorrect()
                    assertTrue(model.busy)
                }
            } finally {
                gate.countDown()
                exportGate = null
            }
            settle(model)
            assertTrue(earlyReview(model).early)
            if (rule.onAllNodesWithTag("review:coach").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithTag("review:coach").performClick()
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            assertUnavailableCard(model)
            assertEquals(before, model.continuous)
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

    /**
     * Six sides mapped with U, F and R fully read and one unread sticker on each of B, L and D, so the hand-over keeps
     * U, F and R. (Below six sides the mapper commits centres only, and no face is kept.)
     */
    private fun showThreeFullyReadFaces() =
        show(Face.entries, blanks = setOf(FaceSlot(Face.B, 0), FaceSlot(Face.L, 0), FaceSlot(Face.D, 0)))

    @Test fun scanFaceByFaceBelowSixSidesAsksForEveryCentreOnlyFaceAndTheReviewHasNoBlanks() {
        quickScanActivity { scan, model ->
            // As the mapper exports three sides: the centres only, eight unread stickers on each mapped face.
            show(listOf(Face.U, Face.F, Face.R), centresOnly = true)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 3/6")
            rule.onNodeWithTag("quickScan:faceByFace").performClick()
            settle(model)
            rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 6")
            waitForCamera(continuous = false)
            listOf(Color.GREEN, Color.RED, Color.BLUE, Color.ORANGE, Color.WHITE, Color.YELLOW).forEach { color ->
                camera.feed(ScanFrame(stats(color), 0))
                shutter(model)
            }
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                model.phase is GuidedScanPhase.Review
            }
            // No sticker is left blank, so the scan can be accepted.
            assertTrue((model.phase as GuidedScanPhase.Review).review.blanks.isEmpty())
            if (rule.onAllNodesWithTag("review:coach").fetchSemanticsNodes().isNotEmpty()) {
                rule.onNodeWithTag("review:coach").performClick()
            }
            rule.onNodeWithTag("review:accept").performScrollTo().performClick()
            assertEquals(ScanOutcome.Accepted(CubeState.SOLVED), outcome(scan.get()))
            val run = quickScanRuns().single()
            assertTrue(run, " fallbackFaces=6 " in "$run " && " outcome=ACCEPTED " in "$run ")
        }
    }

    @Test fun scanFaceByFaceWithThreeFullyReadFacesAsksOnlyTheOtherFacesAndAccepts() {
        quickScanActivity { scan, model ->
            rule.onNodeWithTag("quickScan:faceByFace").assertIsDisplayed()
            showThreeFullyReadFaces()
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
            val run = quickScanRuns().single()
            assertTrue(run, " fallbackFaces=3 " in "$run " && " outcome=ACCEPTED " in "$run ")
            // A scan that never opened an early review carries no such mark.
            assertTrue(run, "earlyReview" !in run)
        }
    }

    /** The activity's Quick Scan run lines (debug diagnostics): one per finished scan. */
    private fun quickScanRuns() = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }.filter { it.startsWith("quickScan run ") }

    /** The haptics performed on the views of [activity]'s window (Robolectric keeps the last one of each view). */
    private fun hapticsPerformed(activity: GuidedScanActivity): List<Int> {
        val views = mutableListOf<View>(activity.window.decorView)
        var index = 0
        while (index < views.size) {
            (views[index++] as? ViewGroup)?.let { group -> repeat(group.childCount) { views += group.getChildAt(it) } }
        }
        return views.map { shadowOf(it).lastHapticFeedbackPerformed() }.filter { it != -1 }
    }

    @Test fun aNewlyMappedSidePlaysOneHapticThroughTheActivity() {
        quickScanActivity { scan, model ->
            settle(model)
            assertEquals(emptyList<Int>(), hapticsPerformed(scan.get()))
            show(listOf(Face.U))
            settle(model)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 1/6")
            assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP), hapticsPerformed(scan.get()))
        }
    }

    @Test fun theDiscardQuestionHoldsACompletedScanUntilItIsCancelled() {
        quickScanActivity { _, model ->
            show(Face.entries, ContinuousScanCue.AllVerified)
            rule.onNodeWithTag("scanClose").performClick()
            assertQuickScanDiscardCopy()
            // The 1000 ms hold ends with the question open: no review until the Learner answers.
            advanceHold(model)
            rule.onNodeWithTag("scan:discard").assertIsDisplayed()
            rule.onNodeWithTag("quickScan:count").assertExists()
            rule.onNodeWithTag("review:accept").assertDoesNotExist()
            assertEquals(GuidedScanPhase.Continuous, model.phase)
            rule.onNodeWithTag("scan:discard:cancel").performClick()
            advanceHold(model)
            rule.onNodeWithTag("scan:discard").assertDoesNotExist()
            rule.onNodeWithTag("review:accept").assertExists()
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
            val run = quickScanRuns().single()
            assertTrue(run, " fallbackFaces=0 " in "$run " && " outcome=CANCELLED " in "$run ")
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
            showThreeFullyReadFaces()
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
            showThreeFullyReadFaces()
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

    @Test fun closingAnAnchoredCentresOnlyHandOverWithNoCaptureAsksFirst() {
        quickScanActivity { scan, model ->
            // Three sides mapped as the mapper exports them below six: no face is kept, yet Back would restore this map.
            show(listOf(Face.U, Face.F, Face.R), centresOnly = true)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 3/6")
            rule.onNodeWithTag("quickScan:faceByFace").performClick()
            settle(model)
            rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 6")
            rule.onNodeWithTag("scanClose").performClick()
            assertQuickScanDiscardCopy()
            assertTrue(!scan.get().isFinishing)
            // Cancel keeps the hand-over, and Back from it still returns to the map.
            rule.onNodeWithTag("scan:discard:cancel").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("scan:discard").assertDoesNotExist()
            rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 6")
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 3/6")
            rule.onNodeWithTag("quickScan:faceByFace").performClick()
            settle(model)
            rule.onNodeWithTag("scanClose").performClick()
            rule.onNodeWithTag("scan:discard:confirm").performClick()
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

    // Review and Correct (PO clarification Q8): six sides mapped, two conflicted stickers and four unread ones.

    /** Conflicted on R (review index 11) and F (index 21). */
    private val conflicted = listOf(FaceSlot(Face.F, 3), FaceSlot(Face.R, 2))

    /** Unread on U (indices 0 and 1), D (index 35) and B (index 52). */
    private val unread = setOf(FaceSlot(Face.U, 0), FaceSlot(Face.U, 1), FaceSlot(Face.D, 8), FaceSlot(Face.B, 7))
    private val earlyBlanks = listOf(0, 1, 11, 21, 35, 52)

    /** The cue names the face of the first conflicted slot in sorted order (R), as the mapper does. */
    private fun showStalled(cue: ContinuousScanCue = ContinuousScanCue.ResolveConflict(Face.R)) =
        show(Face.entries, cue, blanks = conflicted.toSet() + unread, conflicts = conflicted)

    /** Taps *Review and Correct* and returns the early review it opened. */
    private fun openEarlyReview(model: GuidedScanViewModel): GuidedScanPhase.Review {
        rule.onNodeWithTag("quickScan:reviewAndCorrect").assertIsDisplayed().performClick()
        settle(model)
        if (rule.onAllNodesWithTag("review:coach").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithTag("review:coach").performClick()
        return model.phase as GuidedScanPhase.Review
    }

    private fun earlyReview(model: GuidedScanViewModel) = model.phase as GuidedScanPhase.Review

    private fun paint(color: Color) = rule.onNodeWithTag("swatch:${color.name}").performScrollTo().performClick()

    @Test fun reviewAndCorrectOpensTheReviewWithTheConflictedStickersFirstAndAcceptFinishesTheScan() {
        camera.flash = true
        quickScanActivity { scan, model ->
            showStalled()
            rule.onNodeWithTag("quickScan:torch").assert(hasText("Torch off")).performClick()
            settle(model)
            assertEquals(true, camera.torchCalls.last())
            assertTrue(camera.analyzing())
            val opened = openEarlyReview(model)
            // The fresh export: confirmed stickers as read, the two conflicted and four unread ones blank, none guessed.
            assertTrue(opened.early)
            assertEquals(earlyBlanks, opened.review.blanks)
            val solved = CubeState.SOLVED.toFacelets().colors
            opened.review.stickers.forEachIndexed { index, color -> if (color != null) assertEquals(solved[index], color) }
            // The first conflicted sticker is selected, although two unread blanks come before it in sticker order.
            assertEquals(11, opened.review.selected)
            // The torch is off, the camera has stopped and no frame is analysed while the review is open.
            assertEquals(false, model.torchOn)
            assertEquals(false, camera.torchCalls.last())
            assertEquals(null, camera.receiver)
            assertTrue(!camera.analyzing())
            rule.onNodeWithTag("review:accept").performScrollTo().assertIsNotEnabled()
            // Setting a colour goes to the other conflicted sticker, then to the unread ones in the usual order.
            paint(Color.RED)
            assertEquals(21, earlyReview(model).review.selected)
            paint(Color.GREEN)
            assertEquals(35, earlyReview(model).review.selected)
            paint(Color.YELLOW)
            assertEquals(52, earlyReview(model).review.selected)
            paint(Color.BLUE)
            assertEquals(0, earlyReview(model).review.selected)
            paint(Color.WHITE)
            assertEquals(1, earlyReview(model).review.selected)
            paint(Color.WHITE)
            assertTrue(earlyReview(model).early)
            rule.onNodeWithTag("review:accept").performScrollTo().performClick()
            assertEquals(ScanOutcome.Accepted(CubeState.SOLVED), outcome(scan.get()))
            val run = quickScanRuns().single()
            assertTrue(run, " fallbackFaces=0 " in "$run " && " outcome=ACCEPTED " in "$run ")
            // The run line tells a scan finished by hand in an early review from one the camera finished.
            assertTrue(run, " earlyReview=1 " in "$run ")
            // On that line each blank filled by hand counts as a correction, like a sticker changed by hand.
            assertTrue(run, " corrections=6 " in "$run ")
        }
    }

    @Test fun backFromACalibrationOpenedInTheEarlyReviewReturnsToItAndThenToTheLiveScanWithTheTorchOff() {
        camera.flash = true
        quickScanActivity { scan, model ->
            showStalled()
            val opened = openEarlyReview(model)
            rule.runOnIdle { model.phase = opened.copy(offerCalibration = true) }
            val offered = earlyReview(model)
            rule.onNodeWithTag("review:calibrate:offer").performScrollTo().performClick()
            rule.onNodeWithTag("review:calibrate:confirm").performClick()
            settle(model)
            // A calibration opened from the review starts with the torch on (Story 10.21).
            assertTrue(model.phase is GuidedScanPhase.Calibration)
            assertEquals(true, model.torchOn)
            waitForCamera(continuous = false)
            settle(model)
            assertEquals(true, camera.torchCalls.last())
            // Back leaves the calibration for the same early review.
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            assertTrue(model.phase === offered)
            assertTrue(earlyReview(model).early)
            rule.onNodeWithTag("review:accept").assertExists()
            // Back again returns to the live scan, and turns off the torch the calibration had lit.
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            assertTrue(!scan.get().isFinishing)
            assertEquals(GuidedScanPhase.Continuous, model.phase)
            assertEquals(false, model.torchOn)
            waitForCamera(continuous = true)
            settle(model)
            assertEquals(false, camera.torchCalls.last())
            rule.onNodeWithTag("quickScan:torch").assert(hasText("Torch off"))
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 6/6")
        }
    }

    @Test fun enterColorsInACalibrationOpenedFromTheEarlyReviewStillMarksTheRunAsAnEarlyReview() {
        quickScanActivity { scan, model ->
            showStalled()
            val opened = openEarlyReview(model)
            rule.runOnIdle { model.phase = opened.copy(offerCalibration = true) }
            rule.onNodeWithTag("review:calibrate:offer").performScrollTo().performClick()
            rule.onNodeWithTag("review:calibrate:confirm").performClick()
            settle(model)
            assertTrue(model.phase is GuidedScanPhase.Calibration)
            // The scan ends from the calibration, not from the review itself: the run line still carries the mark.
            rule.onNodeWithTag("scanEnterColors").performClick()
            assertEquals(ScanOutcome.FellBackToEditor(opened.review.stickers), outcome(scan.get()))
            val run = quickScanRuns().single()
            assertTrue(run, " outcome=FELL_BACK_TO_EDITOR " in "$run " && " earlyReview=1 " in "$run ")
        }
    }

    @Test fun enterColorsInTheEarlyReviewKeepsItsStickersForTheEditorAndAsksNothing() {
        quickScanActivity { scan, model ->
            showStalled()
            openEarlyReview(model)
            paint(Color.RED)
            val stickers = earlyReview(model).review.stickers
            assertEquals(Color.RED, stickers[11])
            assertEquals(earlyBlanks - 11, earlyReview(model).review.blanks)
            rule.onNodeWithTag("review:enterColors").performScrollTo().performClick()
            rule.onNodeWithTag("review:discard").assertDoesNotExist()
            assertEquals(ScanOutcome.FellBackToEditor(stickers), outcome(scan.get()))
            val run = quickScanRuns().single()
            assertTrue(run, " outcome=FELL_BACK_TO_EDITOR " in "$run " && " earlyReview=1 " in "$run ")
        }
    }

    @Test fun aRotationKeepsTheEarlyReviewWithItsColoursAndBackStillReturnsToTheLiveScan() {
        quickScanActivity { scan, model ->
            showStalled()
            val before = model.continuous
            openEarlyReview(model)
            paint(Color.RED)
            scan.recreate()
            settle(model)
            assertTrue(ViewModelProvider(scan.get())[GuidedScanViewModel::class.java] === model)
            val after = earlyReview(model)
            assertTrue(after.early)
            assertEquals(Color.RED, after.review.stickers[11])
            assertEquals(earlyBlanks - 11, after.review.blanks)
            assertEquals(21, after.review.selected)
            rule.onNodeWithTag("review:accept").assertExists()
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            rule.onNodeWithTag("review:discard").assertDoesNotExist()
            assertTrue(!scan.get().isFinishing)
            assertEquals(GuidedScanPhase.Continuous, model.phase)
            assertTrue(model.continuous === before)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 6/6")
            rule.onNodeWithTag("quickScan:cue").assertTextEquals("Show the red face clearly to resolve a conflict.")
            assertEquals(1, enginesCreated)
            assertEquals(0, engineResets)
        }
    }

    @Test fun theEarlyReviewCorrectsWronglyReadStickersAndAcceptFinishesTheScan() {
        quickScanActivity { scan, model ->
            // All 54 stickers are set, but U1 and R1 were read as each other's colour: no blank, and no valid cube.
            val full = testHandoff(Face.entries)

            fun misread(
                face: Face,
                color: Color,
            ) = full.faces.getValue(face).let { it.copy(colors = it.colors.mapIndexed { cell, read -> if (cell == 0) color else read }) }
            val wrong = full.copy(faces = full.faces + (Face.U to misread(Face.U, Color.RED)) + (Face.R to misread(Face.R, Color.WHITE)))
            exported = ContinuousScanExport.Partial(wrong)
            camera.feed(
                ScanFrame(
                    stats(Color.WHITE),
                    0,
                    continuous = ContinuousFrame(testSnapshot(wrong, ContinuousScanCue.KeepTurningRealign), emptyList()),
                ),
            )
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 6/6")
            val opened = openEarlyReview(model)
            assertTrue(opened.early)
            assertTrue(opened.review.blanks.isEmpty())
            assertEquals(Color.RED to Color.WHITE, opened.review.stickers[0] to opened.review.stickers[9])
            rule.onNodeWithTag("review:accept").performScrollTo().assertIsNotEnabled()
            // The Learner picks each wrong sticker (the first of its face) and sets its colour.
            rule.onNodeWithTag("editor:face:${Face.U.ordinal}").performScrollTo().performClick()
            assertEquals(0, earlyReview(model).review.selected)
            paint(Color.WHITE)
            rule.onNodeWithTag("review:accept").performScrollTo().assertIsNotEnabled()
            rule.onNodeWithTag("editor:face:${Face.R.ordinal}").performScrollTo().performClick()
            assertEquals(9, earlyReview(model).review.selected)
            paint(Color.RED)
            assertEquals(CubeState.SOLVED.toFacelets().colors, earlyReview(model).review.stickers)
            rule.onNodeWithTag("review:accept").performScrollTo().performClick()
            assertEquals(ScanOutcome.Accepted(CubeState.SOLVED), outcome(scan.get()))
            val run = quickScanRuns().single()
            assertTrue(run, " corrections=2 " in "$run " && " outcome=ACCEPTED " in "$run " && " earlyReview=1 " in "$run ")
        }
    }

    @Test fun backFromTheEarlyReviewReturnsToTheLiveScanAsItWasAndDropsItsColours() {
        camera.flash = true
        quickScanActivity { scan, model ->
            showStalled()
            rule.onNodeWithTag("quickScan:torch").performClick()
            settle(model)
            val before = model.continuous
            openEarlyReview(model)
            paint(Color.RED)
            paint(Color.GREEN)
            assertEquals(listOf(0, 1, 35, 52), earlyReview(model).review.blanks)
            // Back asks nothing, although the review was changed.
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            rule.onNodeWithTag("review:discard").assertDoesNotExist()
            rule.onNodeWithTag("scan:discard").assertDoesNotExist()
            assertTrue(!scan.get().isFinishing)
            assertEquals(GuidedScanPhase.Continuous, model.phase)
            // The camera-built map, the count and the cue are the ones shown before the tap; the engine was not touched.
            assertTrue(model.continuous === before)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 6/6")
            rule.onNodeWithTag("quickScan:cue").assertTextEquals("Show the red face clearly to resolve a conflict.")
            assertEquals(0, engineResets)
            assertEquals(1, enginesCreated)
            // The torch stays off.
            rule.onNodeWithTag("quickScan:torch").assert(hasText("Torch off"))
            assertEquals(false, model.torchOn)
            waitForCamera(continuous = true)
            assertEquals(false, camera.torchCalls.last())
            assertTrue(!model.analysisHeld && !model.busy && camera.analyzing())
            // Opening it again shows the camera-built map without the colours set before.
            val again = openEarlyReview(model)
            assertEquals(earlyBlanks, again.review.blanks)
            assertEquals(11, again.review.selected)
            assertTrue(again.corrections.isEmpty() && !again.edited)
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            // Scanning goes on: the next frame is analysed and shown.
            waitForCamera(continuous = true)
            show(Face.entries.take(5))
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 5/6")
        }
    }

    @Test fun aRetakeFinishedInTheEarlyReviewIsDroppedOnBack() {
        quickScanActivity { scan, model ->
            showStalled()
            val opened = openEarlyReview(model)
            // Back keeps its nearer meanings first: it leaves retake mode, then a retake viewfinder, for this review.
            rule.onNodeWithTag("review:retakeFaces").performScrollTo().performClick()
            rule.onNodeWithTag("review:retake:start").assertExists()
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            rule.onNodeWithTag("review:retake:start").assertDoesNotExist()
            assertTrue(model.phase === opened)

            fun startRetakeOfGreen() {
                rule.onNodeWithTag("review:retakeFaces").performScrollTo().performClick()
                rule.onNodeWithTag("reviewNet:face:${Face.F.ordinal}").performClick()
                rule.onNodeWithTag("review:retake:start").performScrollTo().performClick()
                rule.waitUntil(10_000) {
                    shadowOf(Looper.getMainLooper()).idle()
                    (model.phase as? GuidedScanPhase.Viewfinder)?.fromReview != null
                }
                waitForCamera(continuous = false)
            }
            startRetakeOfGreen()
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            assertTrue(model.phase === opened)
            // A finished retake of the green face fills its conflicted sticker; the review is still the early one.
            startRetakeOfGreen()
            camera.feed(ScanFrame(stats(Color.GREEN), 0))
            shutter(model)
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                model.phase is GuidedScanPhase.Review
            }
            val retaken = earlyReview(model)
            assertTrue(retaken.early)
            assertEquals(earlyBlanks - 21, retaken.review.blanks)
            assertEquals(Color.GREEN, retaken.review.stickers[21])
            assertEquals(11, retaken.review.selected)
            // The early review is read with the hand-off's own centres, so one retaken face does not bring up the calibration offer.
            assertEquals(opened.calibration, retaken.calibration)
            assertTrue(retaken.calibration != null)
            assertTrue(!retaken.offerCalibration)
            rule.onNodeWithTag("review:calibrate:offer").assertDoesNotExist()
            // Back drops the retake with the rest of the review.
            rule.onNodeWithTag("review:accept").assertExists()
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            assertTrue(!scan.get().isFinishing)
            assertEquals(GuidedScanPhase.Continuous, model.phase)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 6/6")
            waitForCamera(continuous = true)
            val again = openEarlyReview(model)
            assertEquals(earlyBlanks, again.review.blanks)
            assertEquals(null, again.review.stickers[21])
            assertTrue(again.samples.isEmpty())
        }
    }

    @Test fun backReturnsToTheLiveScanEvenBeforeTheEarlyReviewIsDrawn() {
        quickScanActivity { scan, model ->
            showStalled()
            val before = model.continuous
            // The phase is the early review, but its screen is not composed yet: Back is then the activity's own.
            rule.runOnIdle {
                model.phase =
                    GuidedScanPhase.Review(
                        dev.cubentia.domain.recognition.ScanReview
                            .from(exported.handoff),
                        early = true,
                    )
                scan.get().onBackPressedDispatcher.onBackPressed()
            }
            settle(model)
            assertTrue(!scan.get().isFinishing)
            assertEquals(GuidedScanPhase.Continuous, model.phase)
            assertTrue(model.continuous === before)
        }
    }

    @Test fun closeInTheEarlyReviewAlwaysAsksAndDiscardCancelsTheScan() {
        quickScanActivity { scan, model ->
            showStalled()
            openEarlyReview(model)
            // Nothing was changed, and Close still asks "Discard this scan?".
            rule.onNodeWithTag("review:close").performScrollTo().performClick()
            rule.onNodeWithTag("review:discard").assertIsDisplayed()
            rule.onNodeWithText("Discard this scan?").assertIsDisplayed()
            assertTrue(!scan.get().isFinishing)
            rule.onNodeWithTag("review:discard:cancel").performClick()
            rule.onNodeWithTag("review:discard").assertDoesNotExist()
            // Cancel stays in the review with its work.
            paint(Color.RED)
            rule.onNodeWithTag("review:close").performScrollTo().performClick()
            rule.onNodeWithTag("review:discard:cancel").performClick()
            assertTrue(!scan.get().isFinishing)
            assertEquals(Color.RED, earlyReview(model).review.stickers[11])
            assertTrue(earlyReview(model).early)
            rule.onNodeWithTag("review:close").performScrollTo().performClick()
            rule.onNodeWithTag("review:discard:confirm").performClick()
            assertEquals(ScanOutcome.Cancelled, outcome(scan.get()))
        }
    }

    @Test fun underALatchedTwistReviewAndCorrectStaysAndBackReturnsToTheWaitingScan() {
        quickScanActivity { scan, model ->
            showStalled(ContinuousScanCue.LayerTwistDetected)
            exported = ContinuousScanExport.TwistDetected(1, exported.handoff)
            assertTrue(model.analysisHeld)
            val opened = openEarlyReview(model)
            assertTrue(opened.early)
            assertEquals(earlyBlanks, opened.review.blanks)
            assertEquals(11, opened.review.selected)
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            // The twist is still latched: the kept net and count, the twist cue, and no frame analysed until Reset.
            assertEquals(GuidedScanPhase.Continuous, model.phase)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 6/6")
            rule.onNodeWithTag("quickScan:cue").assertTextEquals("A layer turned during the scan. Hold the layers steady and scan again.")
            rule.onNodeWithTag("quickScan:reviewAndCorrect").assertIsDisplayed()
            waitForCamera(continuous = true)
            assertTrue(model.analysisHeld && !camera.analyzing())
            assertEquals(0, engineResets)
            rule.onNodeWithTag("quickScan:reset").performClick()
            settle(model)
            assertEquals(1, engineResets)
            assertTrue(!model.analysisHeld && camera.analyzing())
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 0/6")
            rule.onNodeWithTag("quickScan:reviewAndCorrect").assertDoesNotExist()
        }
    }

    @Test fun reviewAndCorrectIsNotOfferedBelowItsLimitsOrDuringTheCompletionHold() {
        quickScanActivity { _, model ->
            rule.onNodeWithTag("quickScan:reviewAndCorrect").assertDoesNotExist()
            show(Face.entries.take(3))
            rule.onNodeWithTag("quickScan:reviewAndCorrect").assertDoesNotExist()
            show(Face.entries.take(4))
            rule.onNodeWithTag("quickScan:reviewAndCorrect").assertIsDisplayed()
            show(Face.entries.take(3))
            rule.onNodeWithTag("quickScan:reviewAndCorrect").assertDoesNotExist()
            // A call that arrives with the action hidden opens nothing.
            rule.runOnIdle { model.reviewAndCorrect() }
            settle(model)
            assertEquals(GuidedScanPhase.Continuous, model.phase)
            // The completion hold: Review opens by itself, as the completed scan's review.
            show(Face.entries, ContinuousScanCue.AllVerified)
            rule.onNodeWithTag("quickScan:cue").assertTextEquals("All 54 stickers verified.")
            rule.onNodeWithTag("quickScan:reviewAndCorrect").assertDoesNotExist()
            advanceHold(model)
            assertTrue(!earlyReview(model).early)
        }
    }

    @Test fun anExportThatIsAlreadyCompleteOpensTheCompletedScansReview() {
        quickScanActivity { scan, model ->
            showStalled()
            // The mapper finished between the shown frame and the tap: the fresh export is the completed scan.
            val full = testHandoff(Face.entries)
            exported = ContinuousScanExport.Complete(CubeState.SOLVED, CubeState.SOLVED.toFacelets(), full)
            val opened = openEarlyReview(model)
            assertTrue(!opened.early)
            assertTrue(opened.review.blanks.isEmpty() && opened.review.checkFirst.isEmpty())
            // The completed review's own rule: Close with no change asks nothing and keeps the scan for the Editor.
            rule.onNodeWithTag("review:close").performScrollTo().performClick()
            rule.onNodeWithTag("review:discard").assertDoesNotExist()
            assertEquals(ScanOutcome.FellBackToEditor(full.toFallbackStickers()), outcome(scan.get()))
        }
    }

    // ---- Story 10.34: the debug Quick Scan session recording, through the real activity and view model ----

    private fun session(model: GuidedScanViewModel) = model.session as QuickScanSessionController

    /** Lets the session's io thread, the analyzer thread and the main looper run until [done]. */
    private fun awaitSession(
        model: GuidedScanViewModel,
        done: () -> Boolean,
    ) = rule.waitUntil(10_000) {
        settle(model)
        done()
    }

    private fun rec(model: GuidedScanViewModel) {
        rule.onNodeWithTag("quickScan:rec").assertTextEquals("Rec").performClick()
        awaitSession(model) { session(model).phase is SessionPhase.Recording }
    }

    @Test fun recClearsTheMapAndNoFrameIsAnalysedWhileTheSessionSheetIsOpen() {
        quickScanActivity { _, model ->
            show(listOf(Face.U, Face.R))
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 2/6")
            val resets = engineResets
            rec(model)
            // Answer 3: Rec clears the map and the engine records from frame 0.
            assertTrue(engineResets > resets)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 0/6")
            rule.onNodeWithTag("quickScan:recIndicator").assertExists()
            show(listOf(Face.U))
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 1/6")
            rule.onNodeWithTag("quickScan:rec").assertTextEquals("Stop").performClick()
            awaitSession(model) { session(model).phase is SessionPhase.Stopped }
            rule.onNodeWithTag("quickScan:sessionSheet").assertExists()
            assertTrue(!camera.analyzing())
            show(listOf(Face.U, Face.R, Face.F))
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 1/6")
            rule.onNodeWithTag("session:discard").performScrollTo().performClick()
            rule.onNodeWithTag("session:discard:confirm").performClick()
            awaitSession(model) { session(model).phase == SessionPhase.Idle }
            assertTrue(camera.analyzing())
            show(listOf(Face.U, Face.R, Face.F))
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 3/6")
        }
    }

    @Test fun completionWhileRecordingOpensTheSheetAndScanReviewWaitsForSaveAndDismiss() {
        quickScanActivity { _, model ->
            rec(model)
            show(Face.entries, ContinuousScanCue.AllVerified)
            advanceHold(model)
            awaitSession(model) { session(model).phase is SessionPhase.Stopped }
            assertEquals(
                dev.cubentia.domain.recognition.continuous.session.SessionStopReason.COMPLETE,
                (session(model).phase as SessionPhase.Stopped).reason,
            )
            rule.onNodeWithTag("review:retakeFaces").assertDoesNotExist()
            rule.onNodeWithTag("session:save").performScrollTo().performClick()
            awaitSession(model) { session(model).notice != null }
            advanceHold(model)
            // Answer 13: the hand-off waits for the notice.
            rule.onNodeWithTag("review:retakeFaces").assertDoesNotExist()
            assertEquals(GuidedScanPhase.Continuous, model.phase)
            rule.onNodeWithTag("session:dismiss").performClick()
            advanceHold(model)
            rule.onNodeWithTag("review:retakeFaces").assertExists()
        }
    }

    @Test fun closeAndTheBackgroundStopARecordingAndTheSheetComesBack() {
        quickScanActivity { scan, model ->
            rec(model)
            show(listOf(Face.U))
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            // Stopped first; the sheet waits behind the screen's own "Discard this scan?".
            assertTrue(session(model).phase is SessionPhase.Stopped)
            rule.onNodeWithTag("scan:discard:cancel").assertExists()
            rule.onNodeWithTag("quickScan:sessionSheet").assertDoesNotExist()
            rule.onNodeWithTag("scan:discard:cancel").performClick()
            settle(model)
            rule.onNodeWithTag("quickScan:sessionSheet").assertExists()
            rule.onNodeWithTag("session:save").performScrollTo().performClick()
            awaitSession(model) { session(model).notice != null }
            rec(model)
            scan.pause().stop()
            val stopped = session(model).phase as SessionPhase.Stopped
            assertEquals(dev.cubentia.domain.recognition.continuous.session.SessionStopReason.OPERATOR, stopped.reason)
            scan.start().resume()
            settle(model)
            rule.onNodeWithTag("quickScan:sessionSheet").assertExists()
        }
    }

    @Test fun aBaselineRunFeedsAFreshEngineAndThenRestoresTheEarlierMap() {
        quickScanActivity { _, model ->
            rec(model)
            show(listOf(Face.U, Face.R))
            rule.onNodeWithTag("quickScan:rec").performClick()
            awaitSession(model) { session(model).phase is SessionPhase.Stopped }
            val pending = model.engine
            val created = enginesCreated
            rule.onNodeWithText("Run baseline (30 s)").performScrollTo().performClick()
            awaitSession(model) { session(model).phase is SessionPhase.Baseline }
            assertEquals(created + 1, enginesCreated)
            assertTrue(model.engine !== pending)
            // The camera is rebound to the fresh engine, so the baseline tap sees the real frames.
            assertTrue(camera.engine === model.engine)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 0/6")
            rule.onNodeWithTag("quickScan:reset").assertIsNotEnabled()
            show(listOf(Face.U))
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 1/6")
            // The fresh engine's frames are timed by the baseline tap.
            repeat(5) { i ->
                model.analyzerExecutor
                    .submit { checkNotNull(model.engine).frameDone(i * 33_000_000L, 20_000_000L, 90, 640, 480, 0, 0, 640, 480) }
                    .get()
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
            rule.runOnIdle { session(model).tick() }
            awaitSession(model) { session(model).phase is SessionPhase.Stopped }
            assertTrue(model.engine === pending)
            assertTrue(camera.engine === pending)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 2/6")
            rule.onNodeWithText("Baseline p50 20.0 ms").assertExists()
        }
    }

    /** Rec, two sides, Stop, then Run baseline: the baseline run is live with a fresh engine; the kept engine and map. */
    private fun startBaselineRun(model: GuidedScanViewModel): Pair<ContinuousScanEngine?, Any> {
        rec(model)
        show(listOf(Face.U, Face.R))
        rule.onNodeWithTag("quickScan:rec").performClick()
        awaitSession(model) { session(model).phase is SessionPhase.Stopped }
        val pending = model.engine
        val kept = model.continuous
        rule.onNodeWithText("Run baseline (30 s)").performScrollTo().performClick()
        awaitSession(model) { session(model).phase is SessionPhase.Baseline }
        return pending to kept
    }

    @Test fun aTwistOrACompletionDuringTheBaselineRunNeverHoldsItsAnalysis() {
        quickScanActivity { _, model ->
            startBaselineRun(model)
            show(Face.entries, ContinuousScanCue.AllVerified)
            assertTrue(!model.analysisHeld)
            assertTrue(camera.analyzing())
            show(listOf(Face.U, Face.F, Face.R), ContinuousScanCue.LayerTwistDetected)
            assertTrue(!model.analysisHeld)
            assertTrue(camera.analyzing())
            advanceHold(model)
            assertTrue(session(model).phase is SessionPhase.Baseline)
            assertEquals(GuidedScanPhase.Continuous, model.phase)
        }
    }

    @Test fun aDetectorFailureDuringTheBaselineRunBringsTheKeptEngineAndMapBackBehindTheCard() {
        quickScanActivity { _, model ->
            val (pending, kept) = startBaselineRun(model)
            assertTrue(model.continuous !== kept)
            show(listOf(Face.U))
            feedUnavailable()
            settle(model)
            waitForUnavailableCard()
            assertTrue(model.engine === pending)
            assertTrue(model.continuous === kept)
            assertEquals(SessionPhase.Idle, session(model).phase)
        }
    }

    @Test fun resetWhileRecordingStopsAndTheMapIsClearedOnlyAfterDiscard() {
        quickScanActivity { _, model ->
            rec(model)
            show(listOf(Face.U, Face.R))
            rule.onNodeWithTag("quickScan:reset").performClick()
            awaitSession(model) { session(model).phase is SessionPhase.Stopped }
            assertEquals(
                dev.cubentia.domain.recognition.continuous.session.SessionStopReason.RESET,
                (session(model).phase as SessionPhase.Stopped).reason,
            )
            rule.onNodeWithTag("quickScan:sessionSheet").assertExists()
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 2/6")
            rule.onNodeWithTag("session:discard").performScrollTo().performClick()
            rule.onNodeWithTag("session:discard:confirm").performClick()
            awaitSession(model) { session(model).phase == SessionPhase.Idle }
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 0/6")
        }
    }

    @Test fun scanFaceByFaceWhileRecordingStopsItAndBackReturnsToTheSheet() {
        quickScanActivity { scan, model ->
            rec(model)
            show(listOf(Face.U), anchored = false)
            rule.onNodeWithTag("quickScan:faceByFace").performClick()
            settle(model)
            assertTrue(session(model).phase is SessionPhase.Stopped)
            rule.onNodeWithTag("scanProgress").assertExists()
            rule.onNodeWithTag("quickScan:sessionSheet").assertDoesNotExist()
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            waitForCamera(continuous = true)
            rule.onNodeWithTag("quickScan:sessionSheet").assertExists()
        }
    }

    @Test fun aRotationKeepsARecordingRunning() {
        quickScanActivity { scan, model ->
            rec(model)
            show(listOf(Face.U))
            val engine = model.engine
            scan.recreate()
            waitForCamera(continuous = true)
            settle(model)
            assertTrue(ViewModelProvider(scan.get())[GuidedScanViewModel::class.java] === model)
            assertTrue(session(model).phase is SessionPhase.Recording)
            assertTrue(camera.engine === engine)
            rule.onNodeWithTag("quickScan:recIndicator").assertExists()
        }
    }

    @Test fun resetOnASavedCompletedScansNoticeLetsTheLiveScanRunAgain() {
        quickScanActivity { _, model ->
            rec(model)
            show(Face.entries, ContinuousScanCue.AllVerified)
            advanceHold(model)
            awaitSession(model) { session(model).phase is SessionPhase.Stopped }
            rule.onNodeWithTag("session:save").performScrollTo().performClick()
            awaitSession(model) { session(model).notice != null }
            rule.onNodeWithTag("quickScan:reset").performClick()
            settle(model)
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 0/6")
            assertTrue(camera.analyzing())
            show(listOf(Face.U))
            rule.onNodeWithTag("quickScan:count").assertTextEquals("Sides mapped: 1/6")
            rule.onNodeWithTag("quickScan:rec").assertTextEquals("Rec")
        }
    }

    @Test fun aCancelledDiscardOnASavedCompletedScansNoticeKeepsScanReviewWaiting() {
        quickScanActivity { scan, model ->
            rec(model)
            show(Face.entries, ContinuousScanCue.AllVerified)
            advanceHold(model)
            awaitSession(model) { session(model).phase is SessionPhase.Stopped }
            rule.onNodeWithTag("session:save").performScrollTo().performClick()
            awaitSession(model) { session(model).notice != null }
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            rule.onNodeWithTag("scan:discard:cancel").performClick()
            advanceHold(model)
            // Answer 13: Scan Review still waits for the notice.
            rule.onNodeWithTag("review:retakeFaces").assertDoesNotExist()
            assertEquals(GuidedScanPhase.Continuous, model.phase)
            rule.onNodeWithTag("session:dismiss").performClick()
            advanceHold(model)
            rule.onNodeWithTag("review:retakeFaces").assertExists()
        }
    }

    /**
     * Opens Quick Scan from the Solve tab (`solve:quickScan`) and runs [body] on its activity once it is [ready]: by
     * default once the continuous camera runs (the detector-unavailable card, GH #390, never starts it and waits for the
     * card instead).
     */
    private fun quickScanActivity(
        ready: () -> Unit = { waitForCamera(continuous = true) },
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
                ready()
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
        rule.awaitScanCamera { camera.receiver != null && camera.continuous == continuous }
    }

    private fun stats(color: Color) = FrameStats(List(9) { CellReading(ScanRouteTest.labs.getValue(color), true) }, 120.0, 0.0, 100.0, 12)

    private companion object {
        /** GH #406: the fake engine's timing body, in the debug pipeline's field order. */
        const val TIMING_BODY =
            "frames=3 read=2 fps=30.0 detectorMs=20.0/21.0/21.0 inferenceMs=18.0/19.0/19.0 fitMs=0.5/0.6/0.6 " +
                "sampleMs=1.0/1.1/1.1 mapMs=0.3/0.4/0.4 analyzerMs=22.0/24.0/24.0 fitted=2 observations=2"
    }

    private class FakeFrames : ScanFrameSource {
        @Volatile var receiver: ((ScanFrame) -> Unit)? = null

        @Volatile var continuous = false
        val meterCalls = mutableListOf<Boolean>()
        private lateinit var executor: ExecutorService

        /** How many preview views were made (only a camera phase's screen makes one) and how often the camera was started. */
        var previews = 0

        @Volatile var starts = 0

        override fun preview(context: Context): View {
            previews++
            return View(context)
        }

        /** The activity's own rule for whether a continuous frame is analysed now. */
        @Volatile var analyzing: () -> Boolean = { false }

        /** Story 10.37 E9: the supplier the activity registered, which the real camera reads for every frame. */
        @Volatile var savedCalibration: () -> CalibrationCenters? = { error("no supplier registered") }

        override fun savedCalibration(calibration: () -> CalibrationCenters?) {
            savedCalibration = calibration
        }

        /** The engine the activity last bound, which the real camera would feed (Story 10.34: swapped by a baseline run). */
        @Volatile var engine: ContinuousScanEngine? = null

        override fun continuousMode(
            engine: ContinuousScanEngine?,
            analyzing: () -> Boolean,
        ) {
            continuous = engine != null
            this.engine = engine
            this.analyzing = analyzing
        }

        override fun start(
            owner: ComponentActivity,
            executor: ExecutorService,
            onFrame: (ScanFrame) -> Unit,
            onUnavailable: () -> Unit,
        ) {
            this.executor = executor
            receiver = onFrame
            starts++
        }

        override fun stop() {
            receiver = null
        }

        override fun meter() {
            meterCalls += true
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

        fun feed(
            frame: ScanFrame,
            beforeMainHandlesFrame: () -> Unit = {},
        ) {
            executor.submit { checkNotNull(receiver)(frame) }.get()
            beforeMainHandlesFrame()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
