package dev.cubentia.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.camera.CameraModule
import dev.cubentia.app.camera.GuidedScanActivity
import dev.cubentia.app.camera.GuidedScanFlow
import dev.cubentia.app.camera.GuidedScanPhase
import dev.cubentia.app.camera.GuidedScanViewModel
import dev.cubentia.app.camera.ScanFrame
import dev.cubentia.app.camera.ScanFrameSource
import dev.cubentia.app.di.SolverModule
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.app.solver.SolverLoad
import dev.cubentia.app.solver.SolverSource
import dev.cubentia.app.ui.scan.ScanCoachMarkRecord
import dev.cubentia.app.ui.solve.SolveFlowViewModel
import dev.cubentia.app.ui.solve.SolvePhase
import dev.cubentia.application.port.CalibrationLoad
import dev.cubentia.application.port.CalibrationRepository
import dev.cubentia.application.port.CubeSolver
import dev.cubentia.application.port.SolveResult
import dev.cubentia.application.session.SessionKind
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.GuidedScanPlan
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.ScanStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.ExecutorService
import javax.inject.Inject

@HiltAndroidTest
@UninstallModules(CameraModule::class, SolverModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp")
class ScanRouteTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Inject lateinit var repository: CalibrationRepository

    private val scramble =
        listOf(
            FaceTurn(Face.R, Turn.CLOCKWISE),
            FaceTurn(Face.U, Turn.COUNTERCLOCKWISE),
            FaceTurn(Face.F, Turn.HALF),
            FaceTurn(Face.L, Turn.CLOCKWISE),
        )
    private val cube = CubeState.SOLVED.apply(scramble)

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
        source = FakeFrames()
        denied = false
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA)
    }

    @Test fun scanCalibrationRescanSolvesWithCorrectedStickers() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        rule.waitForIdle()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("welcome:imNew").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("welcome:imNew").performClick()
        rule.onNodeWithTag("nav:solve").performClick()
        rule.onNodeWithTag("solve:scan").performClick()
        rule.waitForIdle()
        rule.waitUntil(10_000) {
            // The denial check runs on Dispatchers.IO and posts its continuation to the Android main looper.
            shadowOf(android.os.Looper.getMainLooper()).idle()
            shadowOf(main.get()).peekNextStartedActivityForResult() != null
        }
        val started = shadowOf(main.get()).nextStartedActivityForResult
        assertEquals(GuidedScanActivity::class.java.name, started.intent.component!!.className)
        val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, started.intent).setup()
        rule.waitForIdle()
        val vm = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
        rule.waitUntil(10_000) { source.starts > 0 }
        rule.waitForIdle()
        assertEquals(1, source.starts)

        captureFaces(vm, logo = true)
        assertTrue(
            "Expected review, got ${vm.phase}; captured=${vm.controller.captured.map {
                it.face
            }}; step=${vm.controller.step}; coaching=${vm.controller.coaching}",
            vm.phase is GuidedScanPhase.Review,
        )
        val first = vm.phase as GuidedScanPhase.Review
        assertTrue(first.offerCalibration)
        assertTrue(first.review.blanks.isNotEmpty())
        val index = first.review.blanks.first { it % 9 != 4 }
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
        assertEquals(2, source.starts)
        val before = source.starts
        source.feed(FrameStats(List(9) { CellReading(labs.getValue(Color.GREEN), true) }, 10.0, 0.0, 100.0, 12), null)
        rule.waitForIdle()
        val coaching = vm.phase as GuidedScanPhase.Calibration
        assertEquals(dev.cubentia.domain.recognition.Coaching.LOW_LIGHT, coaching.coaching)
        assertNull(coaching.session.pending)
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
        assertEquals(before + 1, source.starts)
        captureFaces(vm, logo = false, misread = index)
        val review = vm.phase as GuidedScanPhase.Review
        assertEquals(color, review.review.stickers[index])
        assertTrue(review.edited)
        assertEquals(mapOf(index to color), review.corrections)
        assertFalse(review.offerCalibration)
        ShadowLog.clear()
        rule.onNodeWithTag("review:accept").performClick()
        val result = shadowOf(scan.get())
        shadowOf(main.get()).receiveResult(started.intent, result.resultCode, result.resultIntent)
        scan.pause().stop().destroy()
        rule.waitForIdle()
        rule.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            main
                .get()
                .sessionHost.state.value.kind == SessionKind.SOLVE_PLAYBACK
        }
        val flow = ViewModelProvider(main.get())[SolveFlowViewModel::class.java]
        assertNull(flow.pendingScan.value)
        assertFalse(flow.phase.value is SolvePhase.Editing)
        assertEquals(
            cube,
            main
                .get()
                .sessionHost.state.value.entry,
        )
        rule.onNodeWithTag("editor:accept").assertDoesNotExist()
        rule.onNodeWithTag("solvePlayback:screen").assertExists()
        val records = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }.filter { it.startsWith("guidedScan run ") }
        assertEquals(1, records.size)
        val record =
            Regex(
                """guidedScan run elapsedMs=\d+ repromptFaces=\d+ corrections=1 valid=true outcome=ACCEPTED state=[WYGBRO]{54} initial=[WYGBRO-]{54} device=[A-Za-z0-9-]+""",
            )
        assertTrue(records.single(), record.matches(records.single()))
        val expected =
            cube
                .reoriented(dev.cubentia.domain.cube.Orientation.HOME)
                .toFacelets()
                .colors
                .joinToString("", transform = dev.cubentia.domain.recognition.ScanTrace::letter)
        assertEquals(expected, records.single().substringAfter(" state=").substringBefore(' '))
        // `initial` keeps the camera's original read, so the corrected sticker still differs there.
        val initial = records.single().substringAfter(" initial=").substringBefore(' ')
        assertNotEquals(expected[index], initial[index])
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

    @Test fun repeatedTapsStartOneScanAndACancelledScanCanBeStartedAgain() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        rule.waitForIdle()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("welcome:imNew").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("welcome:imNew").performClick()
        rule.onNodeWithTag("nav:solve").performClick()
        val activity = shadowOf(main.get())

        fun tapTwiceAndTakeTheScan(): Intent {
            rule.onNodeWithTag("solve:scan").performClick()
            rule.onNodeWithTag("solve:scan").performClick()
            rule.waitUntil(10_000) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                activity.peekNextStartedActivityForResult() != null
            }
            val started = activity.nextStartedActivityForResult
            assertEquals(GuidedScanActivity::class.java.name, started.intent.component!!.className)
            // A tap while the scan is open starts nothing either.
            rule.onNodeWithTag("solve:scan").performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            rule.waitForIdle()
            assertNull(activity.peekNextStartedActivityForResult())
            return started.intent
        }

        val first = tapTwiceAndTakeTheScan()
        val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, first).setup()
        rule.waitForIdle()
        rule.waitUntil(10_000) { source.starts > 0 }
        ShadowLog.clear()
        scan.get().onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        val result = shadowOf(scan.get())
        assertTrue(scan.get().isFinishing)
        assertEquals(android.app.Activity.RESULT_CANCELED, result.resultCode)
        val records = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }.filter { it.startsWith("guidedScan run ") }
        assertTrue(" outcome=CANCELLED " in records.single())
        activity.receiveResult(first, result.resultCode, result.resultIntent)
        scan.pause().stop().destroy()
        rule.waitForIdle()
        val flow = ViewModelProvider(main.get())[SolveFlowViewModel::class.java]
        assertEquals(SolvePhase.Home, flow.phase.value)
        val second = tapTwiceAndTakeTheScan()
        activity.receiveResult(second, android.app.Activity.RESULT_CANCELED, null)
        rule.waitForIdle()
        main.pause().stop().destroy()
        rule.waitForIdle()
    }

    @Test fun cameraStopsInReviewAndStaysStoppedAfterRotation() {
        val scan = Robolectric.buildActivity(GuidedScanActivity::class.java).setup()
        try {
            rule.waitForIdle()
            rule.waitUntil(10_000) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                source.running
            }
            val starts = source.starts
            val vm = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
            captureFaces(vm, logo = false)
            val captured = vm.phase as GuidedScanPhase.Review
            rule.onNodeWithTag("review:screen").assertIsDisplayed()
            assertFalse(source.running)
            // A Learner correction made before rotating must survive it.
            val index = captured.review.blanks.first()
            if (rule.onAllNodesWithTag("review:coach").fetchSemanticsNodes().isNotEmpty()) {
                rule.onNodeWithTag("review:coach").performClick()
            }
            rule.onNodeWithTag("sticker:$index").performClick()
            rule.onNodeWithTag("swatch:${cube.toFacelets().colors[index].name}").performClick()
            rule.waitForIdle()
            val review = vm.phase as GuidedScanPhase.Review
            assertEquals(cube.toFacelets().colors[index], review.review.stickers[index])
            assertTrue(review.edited)

            RuntimeEnvironment.setQualifiers("+land")
            scan.recreate()
            rule.waitForIdle()

            assertEquals(
                Configuration.ORIENTATION_LANDSCAPE,
                scan
                    .get()
                    .resources.configuration.orientation,
            )
            assertEquals(review, ViewModelProvider(scan.get())[GuidedScanViewModel::class.java].phase)
            rule.onNodeWithTag("review:screen").assertIsDisplayed()
            assertEquals(starts, source.starts)
            assertFalse(source.running)
        } finally {
            scan.pause().stop().destroy()
        }
    }

    @Test fun misreadEdgeRepromptsTwoFacesWithNamedTopsThenOpensReview() {
        withScan { _, vm ->
            val colors = cube.toFacelets().colors
            // Give UF's two stickers the same color so that the assembled edge is illegal.
            assertNotEquals(colors[7], colors[19])
            GuidedScanPlan.steps.forEach { step ->
                captureFace(vm, step) { index -> labs.getValue(colors[if (index == 7) 19 else index]) }
            }

            val promptTemplate =
                (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded)
                    .bundle.strings
                    .getValue("msg.scan.prompt-with-top")
            val prompts = listOf(Triple(Face.F, "green", "white"), Triple(Face.U, "white", "blue"))
            assertEquals(prompts.size, vm.controller.repromptCount)
            prompts.forEach { (face, center, top) ->
                val phase = vm.phase as GuidedScanPhase.Viewfinder
                assertTrue(vm.controller.repeating)
                assertTrue(source.running)
                val step = GuidedScanPlan.reprompt(face)
                assertEquals(step, phase.step)
                val prompt = promptTemplate.replace("{center}", center).replace("{up}", top)
                rule.onNodeWithText(prompt).assertIsDisplayed()
                captureFace(vm, step) { index -> labs.getValue(colors[index]) }
            }
            rule.onNodeWithTag("review:screen").assertIsDisplayed()
            val review = (vm.phase as GuidedScanPhase.Review).review
            // The retakes replace the misread samples, so the review accepts the scanned cube.
            assertEquals(cube.toFacelets(), review.accepted?.toFacelets())
            assertFalse(vm.controller.repeating)
        }
    }

    @Test fun coachMarkStaysPendingUntilTappedAndIsAbsentInTheNextActivity() {
        val context = RuntimeEnvironment.getApplication()
        context
            .getSharedPreferences("scan_review", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        withScan { _, vm ->
            captureFaces(vm, logo = true)
            rule.onNodeWithTag("review:coach").performScrollTo().assertIsDisplayed()
        }
        assertTrue(ScanCoachMarkRecord.pending(context))

        withScan { _, vm ->
            captureFaces(vm, logo = true)
            rule.onNodeWithTag("review:coach").performScrollTo().performClick()
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                !ScanCoachMarkRecord.pending(context)
            }
        }
        withScan { _, vm ->
            captureFaces(vm, logo = true)
            assertTrue((vm.phase as GuidedScanPhase.Review).review.blanks.isNotEmpty())
            rule.onNodeWithTag("review:coach").assertDoesNotExist()
        }
    }

    @Test fun backFromEditedReviewFinishesOnlyAfterConfirmingDiscard() {
        withScan { activity, vm ->
            captureFaces(vm, logo = true)
            val review = (vm.phase as GuidedScanPhase.Review).review
            val index = review.blanks.first { it % 9 != 4 }
            rule.onNodeWithTag("sticker:$index").performClick()
            rule.onNodeWithTag("swatch:${cube.toFacelets().colors[index].name}").performScrollTo().performClick()
            assertTrue((vm.phase as GuidedScanPhase.Review).edited)
            rule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
            rule.onNodeWithTag("review:discard").assertIsDisplayed()
            assertFalse(activity.isFinishing)
            rule.onNodeWithTag("review:discard:confirm").performClick()
            assertTrue(activity.isFinishing)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        }
    }

    @Test fun backFromUneditedReviewFinishesCancelledWithoutAsking() {
        withScan { activity, vm ->
            captureFaces(vm, logo = true)
            assertFalse((vm.phase as GuidedScanPhase.Review).edited)
            rule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
            rule.onNodeWithTag("review:discard").assertDoesNotExist()
            assertTrue(activity.isFinishing)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        }
    }

    @Test fun retakeFromReviewAsksOnlyTheChosenFaceAndBackRestoresTheReview() {
        withScan { activity, vm ->
            captureFaces(vm, logo = false)
            val review = vm.phase as GuidedScanPhase.Review
            if (rule.onAllNodesWithTag("review:coach").fetchSemanticsNodes().isNotEmpty()) {
                rule.onNodeWithTag("review:coach").performClick()
            }
            rule.onNodeWithTag("review:retakeFaces").performScrollTo().performClick()
            rule.onNodeWithTag("reviewNet:face:${Face.F.ordinal}").performClick()
            rule.onNodeWithTag("review:retake:start").performScrollTo().performClick()
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                vm.phase is GuidedScanPhase.Viewfinder
            }
            val phase = vm.phase as GuidedScanPhase.Viewfinder
            assertTrue(phase.fromReview === review)
            assertEquals(GuidedScanPlan.steps.first { it.face == Face.F }, phase.step)
            rule.onNodeWithTag("scanProgress").assertTextEquals("Face 1 of 1")
            rule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
            rule.waitForIdle()
            assertTrue(vm.phase === review)
            rule.onNodeWithTag("review:screen").assertIsDisplayed()
            assertFalse(activity.isFinishing)
        }
    }

    @Test fun retakeViewfinderCloseRestoresTheReview() {
        withScan { activity, vm ->
            val review = startRetakeOfFront(vm)
            rule.onNodeWithTag("scanClose").performClick()
            rule.waitForIdle()
            assertTrue(vm.phase === review)
            rule.onNodeWithTag("review:screen").assertIsDisplayed()
            assertFalse(activity.isFinishing)
        }
    }

    @Test fun finishedRetakeReturnsToAnEditedReviewWithOnlyTheRetakenFaceReplaced() {
        withScan { activity, vm ->
            val review = startRetakeOfFront(vm)
            val colors = cube.toFacelets().colors
            captureFace(vm, GuidedScanPlan.steps.first { it.face == Face.F }) { index -> labs.getValue(colors[index]) }
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                vm.phase is GuidedScanPhase.Review
            }
            val after = vm.phase as GuidedScanPhase.Review
            assertTrue(after !== review)
            assertTrue(after.edited)
            val front = Face.F.ordinal * 9 until Face.F.ordinal * 9 + 9
            Face.entries.filter { it != Face.F }.forEach { face ->
                val kept = face.ordinal * 9 until face.ordinal * 9 + 9
                assertEquals(review.review.stickers.slice(kept), after.review.stickers.slice(kept))
            }
            // The close call on F's first sticker was retaken with a clear read.
            assertEquals(colors.slice(front), after.review.stickers.slice(front))
            rule.runOnIdle { assertNull(vm.retake) }
            assertTrue(vm.guidedController() === vm.controller)
            assertEquals(GuidedScanFlow.runScanColorsOf(after.samples, vm.saved), vm.measuredCenters)
            rule.onNodeWithTag("review:close").performScrollTo().performClick()
            rule.onNodeWithTag("review:discard").assertIsDisplayed()
            assertFalse(activity.isFinishing)
        }
    }

    /** From a fresh Guided Scan review, starts a retake of the front (green) face; returns the review it started from. */
    private fun startRetakeOfFront(vm: GuidedScanViewModel): GuidedScanPhase.Review {
        captureFaces(vm, logo = false)
        val review = vm.phase as GuidedScanPhase.Review
        if (rule.onAllNodesWithTag("review:coach").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithTag("review:coach").performClick()
        }
        rule.onNodeWithTag("review:retakeFaces").performScrollTo().performClick()
        rule.onNodeWithTag("reviewNet:face:${Face.F.ordinal}").performClick()
        rule.onNodeWithTag("review:retake:start").performScrollTo().performClick()
        rule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            vm.phase is GuidedScanPhase.Viewfinder && source.running
        }
        return review
    }

    private fun withScan(test: (GuidedScanActivity, GuidedScanViewModel) -> Unit) {
        val scan = Robolectric.buildActivity(GuidedScanActivity::class.java).setup()
        try {
            rule.waitForIdle()
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                source.running
            }
            test(scan.get(), ViewModelProvider(scan.get())[GuidedScanViewModel::class.java])
        } finally {
            scan.pause().stop().destroy()
            rule.waitForIdle()
        }
    }

    // [misread] reads that facelet as another clear color, so only the Learner's carried correction can fix it.
    private fun captureFaces(
        vm: GuidedScanViewModel,
        logo: Boolean,
        misread: Int = -1,
    ) {
        val colors = cube.toFacelets().colors
        GuidedScanPlan.steps.forEach { step ->
            captureFace(vm, step, logo) { index ->
                val color = colors[index]
                when {
                    index == misread -> labs.getValue(labs.keys.first { it != color })
                    index == Face.F.ordinal * 9 -> closeCall(color)
                    else -> labs.getValue(color)
                }
            }
        }
    }

    private fun captureFace(
        vm: GuidedScanViewModel,
        step: ScanStep,
        logo: Boolean = false,
        readColor: (Int) -> Lab,
    ) {
        val cells =
            List(9) { index ->
                CellReading(
                    readColor(step.face.ordinal * 9 + index),
                    !(logo && step.centerColor == Color.WHITE && index == 4),
                )
            }
        source.feed(FrameStats(cells, 120.0, 0.0, 100.0, 12), null)
        rule.waitForIdle()
        vm.frozen?.let { vm.endFreeze(it.id) }
        rule.waitForIdle()
    }

    internal class FakeFrames : ScanFrameSource {
        var starts = 0
        private lateinit var executor: ExecutorService
        private var receiver: ((ScanFrame) -> Unit)? = null

        val running: Boolean get() = receiver != null

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

        internal val labs =
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
