package dev.cubentia.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import dev.cubentia.app.scan.ScanFlowContract
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.solver.SolverLoad
import dev.cubentia.app.solver.SolverSource
import dev.cubentia.application.port.CalibrationRepository
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.FrameStats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
import org.robolectric.shadows.ShadowLog
import javax.inject.Inject

/**
 * GH #325: in a plain Guided 6-face viewfinder, *Close* and system Back leave at once with no face captured and ask
 * "Discard the faces you captured?" once one is, for every caller: Welcome (Placement), Solve, Learn's *Find my place*
 * (re-placement) and a Lesson's full Quick Check. *Discard* returns the caller to its launching screen; for Learn that is
 * the Stage list (`learn:findMyPlace`), since the Find my place sheet closes when the scan starts (deferred in the spec).
 */
@HiltAndroidTest
@UninstallModules(CameraModule::class, SolverModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp")
class GuidedScanCloseRouteTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Inject lateinit var repository: CalibrationRepository

    private val cube =
        CubeState.SOLVED.apply(
            listOf(
                FaceTurn(Face.R, Turn.CLOCKWISE),
                FaceTurn(Face.U, Turn.COUNTERCLOCKWISE),
                FaceTurn(Face.F, Turn.HALF),
                FaceTurn(Face.L, Turn.CLOCKWISE),
            ),
        )
    private val camera = ScanRouteTest.FakeFrames()

    @BindValue internal val frames: ScanFrameSource = camera

    @BindValue val launcher: ScanLauncher =
        object : ScanLauncher {
            override fun hasCamera(context: Context) = true

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

    /** A screen that opens the Guided 6-face Scan: how to reach it, how to start the scan, and what it shows again. */
    private enum class Caller(
        val shownAgain: String,
        val quickCheck: Boolean = false,
    ) {
        WELCOME("welcome:startFromCube"),
        SOLVE("solve:scan"),
        LEARN("learn:findMyPlace"),
        LESSON_QUICK_CHECK("confirmCube:scan", quickCheck = true),
    }

    private fun reach(caller: Caller) {
        when (caller) {
            Caller.WELCOME -> {
                waitFor("welcome:startFromCube")
            }

            Caller.SOLVE -> {
                waitFor("welcome:imNew")
                rule.onNodeWithTag("welcome:imNew").performClick()
                waitFor("nav:solve")
                rule.onNodeWithTag("nav:solve").performClick()
                waitFor("solve:scan")
            }

            Caller.LEARN -> {
                waitFor("welcome:imNew")
                rule.onNodeWithTag("welcome:imNew").performClick()
                // *I'm new* may open Stage 1's detail; *Find my place* sits on the Stage list's top bar.
                rule.waitForIdle()
                if (rule.onAllNodesWithTag("stageDetail:back").fetchSemanticsNodes().isNotEmpty()) {
                    rule.onNodeWithTag("stageDetail:back").performClick()
                }
                waitFor("learn:findMyPlace")
            }

            Caller.LESSON_QUICK_CHECK -> {
                waitFor("welcome:imNew")
                rule.onNodeWithTag("welcome:imNew").performClick()
                waitFor("learn:lesson:lesson-01-01")
                rule.onNodeWithTag("learn:lesson:lesson-01-01").performScrollTo().performClick()
                waitFor("lesson:physicalModeSwitch")
                rule.onNodeWithTag("lesson:physicalModeSwitch").performClick()
                waitFor("confirmCube:scan")
            }
        }
    }

    private fun launch(
        caller: Caller,
        main: MainActivity,
    ): Intent {
        when (caller) {
            Caller.LEARN -> {
                rule.onNodeWithTag("learn:findMyPlace").performClick()
                waitFor("findMyPlace:scan")
                rule.onNodeWithTag("findMyPlace:scan").performClick()
            }

            Caller.LESSON_QUICK_CHECK -> {
                rule.onNodeWithTag(caller.shownAgain).performScrollTo().performClick()
            }

            else -> {
                rule.onNodeWithTag(caller.shownAgain).performClick()
            }
        }
        val activity = shadowOf(main)
        rule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            activity.peekNextStartedActivityForResult() != null
        }
        val started = activity.nextStartedActivityForResult.intent
        assertEquals(GuidedScanActivity::class.java.name, started.component?.className)
        // The plain Guided 6-face Scan: never a partial Quick Check; a full one only from the Lesson.
        assertFalse(started.hasExtra(QuickCheckContract.EXTRA_FACES))
        assertEquals(caller.quickCheck, started.getBooleanExtra(QuickCheckContract.EXTRA_FULL_QUICK_CHECK, false))
        return started
    }

    @Test fun welcomeLeavesAtOnceWithNoFace() = leavesAtOnceWithNoFace(Caller.WELCOME)

    @Test fun solveLeavesAtOnceWithNoFace() = leavesAtOnceWithNoFace(Caller.SOLVE)

    @Test fun learnLeavesAtOnceWithNoFace() = leavesAtOnceWithNoFace(Caller.LEARN)

    @Test fun lessonQuickCheckLeavesAtOnceWithNoFace() = leavesAtOnceWithNoFace(Caller.LESSON_QUICK_CHECK)

    @Test fun welcomeAsksBeforeDiscardingFaces() = asksBeforeDiscardingFaces(Caller.WELCOME)

    @Test fun solveAsksBeforeDiscardingFaces() = asksBeforeDiscardingFaces(Caller.SOLVE)

    @Test fun learnAsksBeforeDiscardingFaces() = asksBeforeDiscardingFaces(Caller.LEARN)

    @Test fun lessonQuickCheckAsksBeforeDiscardingFaces() = asksBeforeDiscardingFaces(Caller.LESSON_QUICK_CHECK)

    /** Close, then (in a second scan) Back, with no face captured: no question, Cancelled, the caller shows again. */
    private fun leavesAtOnceWithNoFace(caller: Caller) =
        withMain(caller) { main ->
            listOf<(ActivityController<GuidedScanActivity>) -> Unit>(
                { rule.onNodeWithTag("scanClose").performClick() },
                { scan -> rule.runOnIdle { scan.get().onBackPressedDispatcher.onBackPressed() } },
            ).forEach { leave ->
                val started = launch(caller, main)
                withScan(started) { scan ->
                    assertEquals(0, viewfinder(scan).captured.size)
                    leave(scan)
                    rule.waitForIdle()
                    rule.onNodeWithTag("scan:discard").assertDoesNotExist()
                    assertTrue(scan.get().isFinishing)
                    returnToCaller(caller, main, scan, started)
                }
            }
        }

    /**
     * Two faces captured: Close asks (and the question survives rotation); *Keep scanning* keeps both and a third face
     * is captured; Back asks again; *Discard* ends the scan Cancelled and the caller shows again.
     */
    private fun asksBeforeDiscardingFaces(caller: Caller) =
        withMain(caller) { main ->
            val started = launch(caller, main)
            withScan(started) { scan ->
                captureFaces(scan, 2)
                val asked = viewfinder(scan)
                rule.onNodeWithTag("scanClose").performClick()
                rule.waitForIdle()
                assertAsking(scan)
                // No face is captured behind the question: a clear frame of the prompted face changes nothing.
                feedNextFace(scan)
                rule.waitForIdle()
                assertAsking(scan)
                assertSameScan(asked, viewfinder(scan))

                scan.recreate()
                rule.waitForIdle()
                assertAsking(scan)
                assertSameScan(asked, viewfinder(scan))

                rule.onNodeWithTag("scan:discard:cancel").performClick()
                rule.waitForIdle()
                rule.onNodeWithTag("scan:discard").assertDoesNotExist()
                assertFalse(scan.get().isFinishing)
                assertSameScan(asked, viewfinder(scan))
                awaitCamera()
                captureFaces(scan, 1)
                assertEquals(3, viewfinder(scan).captured.size)

                rule.runOnIdle { scan.get().onBackPressedDispatcher.onBackPressed() }
                rule.waitForIdle()
                assertAsking(scan)
                ShadowLog.clear()
                rule.onNodeWithTag("scan:discard:confirm").performClick()
                rule.waitForIdle()
                assertTrue(scan.get().isFinishing)
                if (!caller.quickCheck) {
                    val records = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }.filter { it.startsWith("guidedScan run ") }
                    assertEquals(1, records.size)
                    assertTrue(records.single(), " outcome=CANCELLED " in records.single())
                }
                returnToCaller(caller, main, scan, started)
            }
        }

    /** A partial Quick Check (EXTRA_FACES) still leaves at once on Close with a face captured (unchanged). */
    @Test fun partialQuickCheckWithAFaceStillLeavesAtOnce() {
        runBlocking { repository.save(CalibrationCenters(ScanRouteTest.labs)) }
        val intent =
            Intent(RuntimeEnvironment.getApplication(), GuidedScanActivity::class.java)
                .putExtra(QuickCheckContract.EXTRA_FACES, arrayOf(Face.U.name, Face.R.name, Face.F.name))
        withScan(intent) { scan ->
            val vm = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
            assertEquals(3, viewfinder(scan).total)
            captureFaces(scan, 1)
            assertEquals(1, viewfinder(scan).captured.size)
            assertFalse(scan.get().isFinishing)
            rule.onNodeWithTag("scanClose").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("scan:discard").assertDoesNotExist()
            assertFalse(vm.confirmDiscard)
            assertTrue(scan.get().isFinishing)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(scan.get()).resultCode)
        }
    }

    /**
     * A Quick Check launched with EXTRA_FACES whose faces could not be used (no saved calibration, no run-scan colors)
     * runs the full six-face plan, so Close with a face captured asks too.
     */
    @Test fun quickCheckFallingBackToTheSixFacePlanAsksBeforeDiscardingFaces() {
        val intent =
            Intent(RuntimeEnvironment.getApplication(), GuidedScanActivity::class.java)
                .putExtra(QuickCheckContract.EXTRA_FACES, arrayOf(Face.U.name, Face.R.name))
        withScan(intent) { scan ->
            assertEquals(6, viewfinder(scan).total)
            captureFaces(scan, 1)
            rule.onNodeWithTag("scanClose").performClick()
            rule.waitForIdle()
            assertAsking(scan)
        }
    }

    /** The scan goes on as it was: same faces captured (in order), same prompt and step. */
    private fun assertSameScan(
        expected: GuidedScanPhase.Viewfinder,
        actual: GuidedScanPhase.Viewfinder,
    ) {
        assertEquals(expected.captured.map { it.face }, actual.captured.map { it.face })
        assertEquals(expected.captured, actual.captured)
        assertEquals(expected.step, actual.step)
        assertEquals(expected.stepIndex, actual.stepIndex)
        assertEquals(expected.again, actual.again)
    }

    private fun assertAsking(scan: ActivityController<GuidedScanActivity>) {
        rule.onNodeWithTag("scan:discard").assertIsDisplayed()
        rule.onNodeWithText("Discard the faces you captured?").assertIsDisplayed()
        rule.onNodeWithTag("scan:discard:confirm").assertTextEquals("Discard")
        rule.onNodeWithTag("scan:discard:cancel").assertTextEquals("Keep scanning")
        assertFalse(scan.get().isFinishing)
    }

    private fun viewfinder(scan: ActivityController<GuidedScanActivity>): GuidedScanPhase.Viewfinder {
        val phase = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java].phase
        return phase as? GuidedScanPhase.Viewfinder ?: throw AssertionError("Expected the viewfinder, got $phase")
    }

    /** Captures the next [count] prompted faces of [cube] with clear reads. */
    private fun captureFaces(
        scan: ActivityController<GuidedScanActivity>,
        count: Int,
    ) {
        val vm = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
        repeat(count) {
            val before = viewfinder(scan).captured.size
            val face = feedNextFace(scan)
            rule.waitForIdle()
            vm.frozen?.let { vm.endFreeze(it.id) }
            rule.waitForIdle()
            assertEquals("Face $face not captured", before + 1, viewfinder(scan).captured.size)
        }
    }

    /** Feeds one clear frame of the prompted face of [cube]; returns that face. */
    private fun feedNextFace(scan: ActivityController<GuidedScanActivity>): Face {
        val face = checkNotNull(viewfinder(scan).step).face
        val colors = cube.toFacelets().colors
        val cells = colors.subList(face.ordinal * 9, face.ordinal * 9 + 9).map { CellReading(ScanRouteTest.labs.getValue(it), true) }
        camera.feed(FrameStats(cells, 120.0, 0.0, 100.0, 12), null)
        return face
    }

    /** The scan ended Cancelled; MainActivity receives it and the caller's launching control shows again. */
    private fun returnToCaller(
        caller: Caller,
        main: MainActivity,
        scan: ActivityController<GuidedScanActivity>,
        started: Intent,
    ) {
        val result = shadowOf(scan.get())
        assertEquals(Activity.RESULT_CANCELED, result.resultCode)
        assertEquals(ScanOutcome.Cancelled, ScanFlowContract.decode(result.resultCode, result.resultIntent))
        shadowOf(main).receiveResult(started, result.resultCode, result.resultIntent)
        scan.pause().stop().destroy()
        rule.waitForIdle()
        waitFor(caller.shownAgain)
        val shown = rule.onNodeWithTag(caller.shownAgain)
        if (caller.quickCheck) shown.performScrollTo()
        shown.assertIsDisplayed()
    }

    private fun withMain(
        caller: Caller,
        body: (MainActivity) -> Unit,
    ) {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            reach(caller)
            body(main.get())
        } finally {
            main.pause().stop().destroy()
        }
    }

    private fun withScan(
        intent: Intent,
        body: (ActivityController<GuidedScanActivity>) -> Unit,
    ) {
        val starts = camera.starts
        val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, intent).setup()
        try {
            rule.awaitScanCamera { camera.starts > starts }
            rule.waitForIdle()
            body(scan)
        } finally {
            if (scan.get().lifecycle.currentState != Lifecycle.State.DESTROYED) scan.pause().stop().destroy()
            rule.waitForIdle()
        }
    }

    /** Waits until the camera runs again (after a recreate). */
    private fun awaitCamera() = rule.awaitScanCamera { camera.running }

    private fun waitFor(tag: String) {
        rule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
