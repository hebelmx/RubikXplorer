package dev.cubentia.app

import android.Manifest
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
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
import dev.cubentia.app.camera.CornerFrame
import dev.cubentia.app.camera.GuidedScanActivity
import dev.cubentia.app.camera.GuidedScanPhase
import dev.cubentia.app.camera.GuidedScanViewModel
import dev.cubentia.app.camera.ScanFrame
import dev.cubentia.app.camera.ScanFrameSource
import dev.cubentia.app.camera.ScanNetFaceCaptured
import dev.cubentia.app.camera.ShutterSound
import dev.cubentia.app.scan.ScanFlowContract
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.ui.solve.SolveFlowViewModel
import dev.cubentia.app.ui.solve.SolvePhase
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
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

    /**
     * Stories 10.19 and 10.22: the activity feeds the corner faces to the domain's light check. A backlit corner is flagged
     * for coaching, and the check holds nothing back: the shutter captures it, logged with its trigger and the failing light.
     */
    @Test fun aBacklitCornerIsCoachedAndStillCapturesOnAPress() {
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
            val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, shadow.nextStartedActivityForResult.intent).setup()
            try {
                waitForCamera(corner = true)
                val model = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
                // Hold 1's sides (Green, Red) 40 L* darker than the White top: the light behind the cube.
                shade = 40.0
                shadedColors = setOf(Color.GREEN, Color.RED)
                // The first frame re-meters onto the cube and flags nothing; the fail starts once metering has settled.
                val dark = QuickScanPlan.holds[0].map { step -> stats(step.centerColor) }
                camera.feed(ScanFrame(dark.first(), 0, corner = CornerFrame(true, dark)))
                assertTrue("nothing flagged on the first frame", !checkNotNull(model.quickScan).lightHeld)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
                camera.feed(ScanFrame(dark.first(), 0, corner = CornerFrame(true, dark)))
                assertTrue("the failing light is flagged", checkNotNull(model.quickScan).lightHeld)
                rule.onNodeWithTag("quickScan:counter").assertTextEquals("1 / 2")
                ShadowLog.clear()
                shutter(model)
                rule.onNodeWithTag("quickScan:counter").assertTextEquals("2 / 2")
                val lines = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }
                val capture = lines.single { it.startsWith("quickScan capture hold=1 ") && it.contains("totalMs=") }
                assertTrue(capture, capture.contains(" trigger=manual ") && capture.contains(" light=fail "))
                assertTrue(lines.toString(), lines.any { Regex("""quickScan capture hold=1 t=\d+ trigger=manual""").matches(it) })
            } finally {
                scan.pause().stop().destroy()
            }
        } finally {
            main.pause().stop().destroy()
        }
    }

    @Test fun captureTwoFallbackPlaysCaptureFlashAndCounterCueBeforeEnteringGuidedGrid() {
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
                // Hold 1 with an uncertain face -> Capture 2 will trigger the Guided hand-over.
                captureHold(1, uncertain = true, model = model)
                rule.onNodeWithTag("quickScan:counter").assertTextEquals("2 / 2")

                // Trigger Hold 2 capture WITHOUT ending the 400 ms freeze yet.
                val hold2Faces = QuickScanPlan.holds[1].map { step -> stats(step.centerColor) }
                camera.feed(ScanFrame(hold2Faces.first(), 0, corner = CornerFrame(true, hold2Faces)))
                rule.onNodeWithTag("scanCapture").performClick()
                model.analyzerExecutor.submit {}.get()
                shadowOf(Looper.getMainLooper()).idle()

                // During the 400 ms freeze, Capture 2 stays on CornerViewfinder with the capture flash cue active.
                assertTrue("Expected freeze to remain active before fallback", model.frozen != null)
                assertTrue(
                    "Expected CornerViewfinder during Capture 2 freeze, got ${model.phase}",
                    model.phase is dev.cubentia.app.camera.GuidedScanPhase.CornerViewfinder,
                )
                rule.onNodeWithTag("quickScan:captureFlash").assertExists()
                rule.onNodeWithTag("quickScan:counter").assertTextEquals("2 / 2")

                // Once the 400 ms freeze ends, it transitions cleanly to Guided fallback.
                rule.runOnIdle { model.frozen?.let { model.endFreeze(it.id) } }
                rule.waitForIdle()
                rule.onNodeWithTag("quickScan:counter").assertDoesNotExist()
                rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 1")
            } finally {
                scan.pause().stop().destroy()
            }
        } finally {
            main.pause().stop().destroy()
        }
    }

    /** AC5: both corner captures play the same shutter sound, once per capture. */
    @Test fun bothCornerCapturesPlayTheShutterSoundOncePerCapture() {
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
                val player = FakeShutterPlayer()
                scan.get().shutterSound = ShutterSound(player).apply { load() }
                waitForCamera(corner = true)
                val model = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
                captureHold(1, uncertain = false, model = model)
                assertEquals(1, player.plays)
                captureHold(2, uncertain = false, model = model)
                assertEquals(2, player.plays)
            } finally {
                scan.pause().stop().destroy()
            }
        } finally {
            main.pause().stop().destroy()
        }
    }

    @Test fun holdOneRemetersWithAwbAndHoldTwoSkipsAwb() {
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
                camera.meterCalls.clear()
                camera.cubeMeterCalls.clear()
                camera.releases.set(0)
                holdSteady(1, model)
                assertEquals("one exposure release per capture", 1, camera.releases.get())
                holdSteady(2, model)
                assertEquals(listOf(true, false), camera.meterCalls)
                assertEquals("both holds meter on the cube", listOf(true, false), camera.cubeMeterCalls.toList())
                assertEquals("one exposure release per capture", 2, camera.releases.get())
            } finally {
                scan.pause().stop().destroy()
            }
        } finally {
            main.pause().stop().destroy()
        }
    }

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
                    rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 1")
                    waitForCamera(corner = false)
                    camera.feed(ScanFrame(stats(Color.GREEN), 0))
                    shutter(model)
                }
                ShadowLog.clear()
                // The combined Quick Scan review offers Retake faces too (Stories 10.16, 10.17).
                rule.onNodeWithTag("review:retakeFaces").assertExists()
                rule.onNodeWithTag("review:accept").performScrollTo().performClick()
                val result = shadowOf(scan.get())
                assertEquals(ScanOutcome.Accepted(CubeState.SOLVED), ScanFlowContract.decode(result.resultCode, result.resultIntent))
                // The accepted run logs the accepted stickers as `state=` (what evaluateMatrix scores) and the read as `initial=`.
                val run = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }.single { it.startsWith("quickScan run ") }
                val solved =
                    CubeState.SOLVED
                        .toFacelets()
                        .colors
                        .joinToString("") { it.name.take(1) }
                assertTrue(run, run.contains(" outcome=ACCEPTED state=$solved initial="))
                assertTrue(run, Regex(" initial=[WYGBRO-]{54} ").containsMatchIn(run))
                shadow.receiveResult(intent, result.resultCode, result.resultIntent)
                rule.waitForIdle()
                val flow = ViewModelProvider(main.get())[SolveFlowViewModel::class.java]
                assertEquals(SolvePhase.AlreadySolved, flow.phase.value)
            } finally {
                scan.pause().stop().destroy()
            }
        } finally {
            main.pause().stop().destroy()
        }
    }

    /** Issue #194, Story 10.17: a hand-over face held upside down is searched over its quarter turns, the kept faces held. */
    @Test fun aReaskedFaceHeldUpsideDownIsAcceptedInTheCornerFrame() {
        val cube = CubeState.SOLVED.apply((Notation.parse("U R2 F' D L' B2") as NotationResult.Parsed).moves)
        val net = { face: Face ->
            cube
                .toFacelets()
                .colors
                .drop(face.ordinal * 9)
                .take(9)
        }
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
                QuickScanPlan.holds.forEach { steps ->
                    // Corner reads in plan frames, as QuickScanControllerTest's hold(); U is unsure so the grid asks for it.
                    val faces =
                        steps.map { step ->
                            val colors = net(step.face)
                            val framed =
                                when (step.face) {
                                    Face.D -> listOf(2, 5, 8, 1, 4, 7, 0, 3, 6).map(colors::get)
                                    Face.L, Face.B -> colors.reversed()
                                    else -> colors
                                }
                            stats(framed, uncertain = step.face == Face.U)
                        }
                    captureFaces(faces, model)
                }
                rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 1")
                waitForCamera(corner = false)
                // Held 180 degrees off the net's top at camera rotation 90: one clockwise quarter turn of the net in pixel order
                // (ScanAssembler.rotateClockwise(net, 1), which is internal to :core:domain).
                val pixels = listOf(6, 3, 0, 7, 4, 1, 8, 5, 2).map(net(Face.U)::get)
                camera.feed(ScanFrame(stats(pixels), 90))
                shutter(model)
                rule.onNodeWithTag("review:accept").performScrollTo().performClick()
                val result = shadowOf(scan.get())
                assertEquals(ScanOutcome.Accepted(cube), ScanFlowContract.decode(result.resultCode, result.resultIntent))
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
                // The exact shape QuickScanMeasurement parses at device session C; a drifted field would void the tally.
                val record =
                    Regex(
                        """quickScan run elapsedMs=\d+ fallbackFaces=0 repromptFaces=0 corrections=1 outcome=CANCELLED state=[WYGBRO-]{54} initial=[WYGBRO-]{54} device=[A-Za-z0-9-]+""",
                    )
                assertTrue(runs.single(), record.matches(runs.single()))
            } finally {
                scan.pause().stop().destroy()
            }
        } finally {
            main.pause().stop().destroy()
        }
    }

    /** Story 10.17 AC1/AC2: capture hold 1, *Scan face by face*, capture the three remaining faces, Accept. */
    @Test fun scanFaceByFaceAfterTheFirstCornerAsksOnlyTheRemainingFacesAndAccepts() {
        quickScanActivity { scan, model ->
            rule.onNodeWithTag("quickScan:faceByFace").assertIsDisplayed()
            captureHold(1, uncertain = false, model = model)
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
            waitForCamera(corner = false)
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
            waitForCamera(corner = false)
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

    /** Story 10.17 PO Q1: Back from the hand-over resumes the corner view; Close asks before discarding kept faces. */
    @Test fun backFromTheHandOverResumesTheCornerViewAndCloseAsksBeforeDiscarding() {
        quickScanActivity { scan, model ->
            captureHold(1, uncertain = false, model = model)
            rule.onNodeWithTag("quickScan:faceByFace").performClick()
            settle(model)
            waitForCamera(corner = false)
            camera.feed(ScanFrame(stats(Color.BLUE), 0))
            shutter(model)
            rule.onNodeWithTag("scanProgress").assertTextEquals("Face 2 of 3")
            // GH #325: Close in the hand-over keeps Quick Scan's own copy; Cancel keeps the hand-over.
            rule.onNodeWithTag("scanClose").performClick()
            assertQuickScanDiscardCopy()
            rule.onNodeWithTag("scan:discard:cancel").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("scan:discard").assertDoesNotExist()
            rule.onNodeWithTag("scanProgress").assertTextEquals("Face 2 of 3")
            scan.get().onBackPressedDispatcher.onBackPressed()
            settle(model)
            waitForCamera(corner = true)
            rule.onNodeWithTag("quickScan:counter").assertTextEquals("2 / 2")
            rule.onNodeWithTag("scanNetFace:${Face.B.name}").assert(SemanticsMatcher.expectValue(ScanNetFaceCaptured, false))
            rule.onNodeWithTag("scanNetFace:${Face.U.name}").assert(SemanticsMatcher.expectValue(ScanNetFaceCaptured, true))
            // Close with kept faces asks; Cancel keeps the scan exactly.
            rule.onNodeWithTag("scanClose").performClick()
            assertQuickScanDiscardCopy()
            rule.onNodeWithTag("scan:discard:cancel").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("scan:discard").assertDoesNotExist()
            assertTrue(!scan.get().isFinishing)
            rule.onNodeWithTag("quickScan:counter").assertTextEquals("2 / 2")
            // System Back in the corner view asks too; Discard ends the scan Cancelled.
            scan.get().onBackPressedDispatcher.onBackPressed()
            rule.waitForIdle()
            rule.onNodeWithTag("scan:discard:confirm").performClick()
            rule.waitForIdle()
            val result = shadowOf(scan.get())
            assertEquals(ScanOutcome.Cancelled, ScanFlowContract.decode(result.resultCode, result.resultIntent))
        }
    }

    /** Quick Scan's discard question keeps "Discard this scan?" / *Cancel*, never the Guided one's copy (GH #325). */
    private fun assertQuickScanDiscardCopy() {
        rule.onNodeWithTag("scan:discard").assertIsDisplayed()
        rule.onNodeWithText("Discard this scan?").assertIsDisplayed()
        rule.onNodeWithText("Discard the faces you captured?").assertDoesNotExist()
        rule.onNodeWithTag("scan:discard:cancel").assertTextEquals("Cancel")
    }

    /** Issue #276: Close from the Guided fallback discards the scan; the next Quick Scan opens a fresh corner view at hold 1. */
    @Test fun closeFromTheGuidedFallbackThenReopenStartsAFreshCornerViewAtHoldOne() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("welcome:imNew").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("welcome:imNew").performClick()
            rule.onNodeWithTag("nav:solve").performClick()
            val shadow = shadowOf(main.get())
            val first = openQuickScan(shadow)
            val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, first).setup()
            try {
                waitForCamera(corner = true)
                val model = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
                rule.onNodeWithTag("quickScan:faceByFace").performClick()
                settle(model)
                rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 6")
                waitForCamera(corner = false)
                camera.feed(ScanFrame(stats(Color.WHITE), 0))
                shutter(model)
                rule.onNodeWithTag("scanProgress").assertTextEquals("Face 2 of 6")
                rule.onNodeWithTag("scanClose").performClick()
                rule.onNodeWithTag("scan:discard:confirm").performClick()
                rule.waitForIdle()
                assertTrue(scan.get().isFinishing)
                val result = shadowOf(scan.get())
                assertEquals(ScanOutcome.Cancelled, ScanFlowContract.decode(result.resultCode, result.resultIntent))
                shadow.receiveResult(first, result.resultCode, result.resultIntent)
                rule.waitForIdle()
            } finally {
                scan.pause().stop().destroy()
            }
            val second = openQuickScan(shadow)
            val again = Robolectric.buildActivity(GuidedScanActivity::class.java, second).setup()
            try {
                waitForCamera(corner = true)
                rule.onNodeWithTag("quickScan:counter").assertTextEquals("1 / 2")
                rule.onNodeWithTag("scanProgress").assertDoesNotExist()
                val model = ViewModelProvider(again.get())[GuidedScanViewModel::class.java]
                assertEquals(GuidedScanPhase.CornerViewfinder::class, model.phase::class)
                assertEquals(1, (model.phase as GuidedScanPhase.CornerViewfinder).hold)
            } finally {
                again.pause().stop().destroy()
            }
        } finally {
            main.pause().stop().destroy()
        }
    }

    /** Taps Quick Scan on the Solve tab and returns the intent it started. */
    private fun openQuickScan(shadow: org.robolectric.shadows.ShadowActivity): android.content.Intent {
        rule.onNodeWithTag("solve:quickScan").performClick()
        rule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            shadow.peekNextStartedActivityForResult() != null
        }
        return shadow.nextStartedActivityForResult.intent
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

    /**
     * Story 10.19 activity wiring: on a camera with a flash the torch is on by default in the corner view and off after
     * *Scan face by face*, it is suspended on stop, and a settle signal still false delays the auto-capture past the fixed
     * settle wait (until the settle cap).
     */
    @Test fun theTorchFollowsThePhasesAndTheSettleSignalDelaysAutoCapture() {
        camera.flash = true
        quickScanActivity { scan, model ->
            val faces = QuickScanPlan.holds[0].map { step -> stats(step.centerColor) }
            val frame = ScanFrame(faces.first(), 0, corner = CornerFrame(true, faces))
            camera.feed(frame)
            rule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("the torch is on by default", true, camera.torchCalls.lastOrNull())
            rule.onNodeWithText("Torch on").assertExists()
            // The torch applied: this frame re-meters; the settle signal then stays false.
            camera.settledSignal = false
            camera.feed(frame)
            val quick = checkNotNull(model.quickScan)
            assertTrue("waiting for the settle signal", quick.settling)
            // With no signal the fixed 600 ms wait would settle at 600 and capture after 1000 ms steady (by 1650).
            listOf(600L, 800L, 250L).forEach { step ->
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
                camera.feed(frame)
            }
            rule.onNodeWithTag("quickScan:counter").assertTextEquals("1 / 2")
            // The settle cap (1500 ms) passed at the last frame: the steady count starts there and captures 1 s later.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1050))
            camera.feed(frame)
            rule.waitForIdle()
            rule.onNodeWithTag("quickScan:counter").assertTextEquals("2 / 2")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            rule.runOnIdle { model.frozen?.let { model.endFreeze(it.id) } }
            // Leaving to the background suspends the torch; coming back resumes it.
            scan.pause().stop()
            assertEquals(true, camera.suspendCalls.lastOrNull())
            scan.start().resume()
            assertEquals(false, camera.suspendCalls.lastOrNull())
            // Scan face by face hands over to the Guided grid: the torch goes off.
            rule.onNodeWithTag("quickScan:faceByFace").performClick()
            settle(model)
            assertEquals("the torch is off on Scan face by face", false, camera.torchCalls.lastOrNull())
        }
    }

    /** Opens Quick Scan from the Solve tab (`solve:quickScan`) and runs [body] on its activity once the corner camera runs. */
    private fun diagLines() = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }

    /** A detected hold-1 frame whose outline (bounding box 300 × 400 px, diagonal 500) is shifted [dx] px. */
    private fun outlined(dx: Double): ScanFrame {
        val faces = QuickScanPlan.holds[0].map { step -> stats(step.centerColor) }
        val p = listOf(150.0 to 0.0, 300.0 to 100.0, 150.0 to 200.0, 0.0 to 100.0, 0.0 to 300.0, 150.0 to 400.0, 300.0 to 300.0)
        val quads =
            listOf(listOf(p[0], p[1], p[2], p[3]), listOf(p[3], p[2], p[5], p[4]), listOf(p[2], p[1], p[6], p[5]))
                .map { quad ->
                    quad.map { (x, y) ->
                        dev.cubentia.app.camera
                            .CornerPoint(x + dx, y)
                    }
                }
        return ScanFrame(faces.first(), 0, corner = CornerFrame(true, faces, frameQuads = quads))
    }

    /** Story 10.22: the corner view's opening and its first frame start the hold's timeline. */
    @Test fun openingTheCornerViewLogsTheViewfinderEntryAndThenTheFirstFrame() {
        quickScanActivity { _, model ->
            model.analyzerExecutor.submit {}.get()
            assertTrue(diagLines().toString(), "quickScan viewfinder hold=1 t=0" in diagLines())
            assertTrue(diagLines().none { it.startsWith("quickScan firstFrame") })
            camera.feed(outlined(0.0))
            assertTrue(diagLines().toString(), diagLines().any { Regex("""quickScan firstFrame hold=1 t=\d+""").matches(it) })
        }
    }

    /** Story 10.22: the activity passes the detected outline on; steadiness and re-metering follow it. */
    @Test fun theOutlineMovingRestartsTheSteadyWindowAndLeavingTheMeteredRegionRemeters() {
        quickScanActivity { _, _ ->
            camera.cubeMeterCalls.clear()
            camera.feed(outlined(0.0))
            assertEquals(listOf(true), camera.cubeMeterCalls.toList())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(650))
            camera.feed(outlined(0.0))
            // 75 px of a 500 px diagonal: beyond the steady fraction, inside the metered region.
            camera.feed(outlined(75.0))
            val restart = Regex("""quickScan steadyRestart hold=1 t=\d+ reason=outline amount=0\.150""")
            assertTrue(diagLines().toString(), diagLines().any(restart::matches))
            assertEquals(1, camera.cubeMeterCalls.size)
            // 175 px from where it was metered: the cube left the metered region, one more metering on the cube.
            camera.feed(outlined(175.0))
            assertEquals(listOf(true, true), camera.cubeMeterCalls.toList())
            val remeter = Regex("""quickScan meteringRequested hold=1 t=\d+ reason=outline amount=0\.350""")
            assertTrue(diagLines().toString(), diagLines().any(remeter::matches))
        }
    }

    /** Story 10.22: a lost cube ends the exposure lock, once. */
    @Test fun losingTheCubeReleasesTheExposureLockOnce() {
        quickScanActivity { _, _ ->
            val gone = ScanFrame(outlined(0.0).stats, 0, corner = CornerFrame(false, List(3) { null }))
            camera.feed(outlined(0.0))
            camera.feed(gone)
            assertEquals("a short gap keeps the lock", 0, camera.releases.get())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
            camera.feed(gone)
            assertEquals(1, camera.releases.get())
            assertTrue(diagLines().toString(), diagLines().any { it.startsWith("quickScan cubeLost hold=1 ") })
            camera.feed(gone)
            assertEquals("released once, as the cube becomes lost", 1, camera.releases.get())
        }
    }

    private fun quickScanActivity(
        body: (org.robolectric.android.controller.ActivityController<GuidedScanActivity>, GuidedScanViewModel) -> Unit,
    ) {
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
                body(scan, ViewModelProvider(scan.get())[GuidedScanViewModel::class.java])
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

    /**
     * Feeds one hold's corner frames over time with no shutter tap: the frames that confirm the corner in view (Story
     * 10.22: hold 2 needs the turn on consecutive frames), remeter, settle, then 1000 ms steady.
     */
    private fun holdSteady(
        hold: Int,
        model: GuidedScanViewModel,
    ) {
        val faces = QuickScanPlan.holds[hold - 1].map { step -> stats(step.centerColor) }
        val frame = ScanFrame(faces.first(), 0, corner = CornerFrame(true, faces))
        repeat(dev.cubentia.domain.recognition.QuickScanController.TURN_CONFIRM_FRAMES) { camera.feed(frame) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(650))
        camera.feed(frame)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1050))
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
        captureFaces(faces, model)
    }

    private fun captureFaces(
        faces: List<FrameStats>,
        model: GuidedScanViewModel,
    ) {
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
    ): FrameStats = stats(List(9) { color }, uncertain)

    /**
     * Story 10.19: the six colors with their a*b* from [ScanRouteTest.labs] but L* spread like a cube in even front light,
     * so the uncalibrated light check (75th-percentile L*, top vs sides) passes every hold of the solved cube these tests
     * scan; [shade] darkens the [shadedColors] (the light behind the cube).
     */
    private val evenLight =
        ScanRouteTest.labs.mapValues { (color, lab) ->
            lab.copy(
                l =
                    when (color) {
                        Color.WHITE -> 85.0
                        Color.YELLOW -> 80.0
                        Color.ORANGE -> 70.0
                        Color.GREEN -> 65.0
                        else -> 60.0
                    },
            )
        }
    private var shade = 0.0
    private var shadedColors = emptySet<Color>()

    private fun stats(
        colors: List<Color>,
        uncertain: Boolean = false,
    ): FrameStats =
        FrameStats(
            colors.mapIndexed { index, color ->
                val lab = evenLight.getValue(color).let { it.copy(l = it.l - if (color in shadedColors) shade else 0.0) }
                CellReading(if (uncertain && index == 0) Lab(55.0, 55.0, 55.0) else lab, true)
            },
            120.0,
            0.0,
            100.0,
            12,
        )

    private class FakeShutterPlayer : ShutterSound.Player {
        var plays = 0

        override fun load() = Unit

        override fun play() {
            plays++
        }

        override fun release() = Unit
    }

    private class FakeCornerFrames : ScanFrameSource {
        @Volatile var receiver: ((ScanFrame) -> Unit)? = null

        @Volatile var corner = false
        val meterCalls = mutableListOf<Boolean>()
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

        override fun meter() {
            meterCalls += true
        }

        override fun meter(includeAwb: Boolean) {
            meterCalls += includeAwb
        }

        /** Story 10.22: each metering on the cube and each exposure release the activity asked for. */
        val cubeMeterCalls = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val releases =
            java.util.concurrent.atomic
                .AtomicInteger()

        override fun meterCube(includeAwb: Boolean) {
            cubeMeterCalls += includeAwb
            meter(includeAwb)
        }

        override fun releaseExposure() {
            releases.incrementAndGet()
        }

        /** Story 10.19: whether the fake camera has a flash unit (null: unknown, as before a camera is bound). */
        @Volatile var flash: Boolean? = null

        /** The settle signal the activity reads per frame (null: no signal, the fixed settle wait applies). */
        @Volatile var settledSignal: Boolean? = null
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

        override fun settled(): Boolean? = settledSignal

        fun feed(frame: ScanFrame) {
            executor.submit { checkNotNull(receiver)(frame) }.get()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
