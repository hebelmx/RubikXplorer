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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import dev.cubentia.app.scan.QuickCheckContract
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.application.port.CalibrationLoad
import dev.cubentia.application.port.CalibrationRepository
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SyncState
import dev.cubentia.domain.cube.Color
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
@UninstallModules(CameraModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp")
class QuickCheckRouteTest {
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

    @Inject lateinit var host: dev.cubentia.app.session.SessionHost

    @Test fun physicalLessonMismatchCapturesSubsetAndReturnsLikelyEvidence() {
        runBlocking {
            repository.save(
                dev.cubentia.domain.recognition
                    .CalibrationCenters(labs),
            )
        }
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            physicalLesson(main)
            rule.onNodeWithTag("confirmCube:matches").performScrollTo().performClick()
            rule.waitUntil(10_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
            val original = host.state.value.expected
            runBlocking {
                host.dispatch {
                    SessionEvent
                        .EditorEntered(original, it)
                }
                host.dispatch {
                    SessionEvent
                        .PhysicalConfirmed(FaceTurn(Face.R, Turn.CLOCKWISE), it)
                }
            }
            rule.waitForIdle()
            rule.onNodeWithTag("lesson:overflowMenu").performScrollTo().performClick()
            rule.onNodeWithTag("lesson:menuMismatch").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("mismatch:quickCheck").assertExists()
            rule.onNodeWithTag("lesson:quickCheck").performScrollTo().performClick()
            rule.waitUntil(10_000) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                shadowOf(main.get()).peekNextStartedActivityForResult() != null
            }
            val pending = host.state.value.pendingQuickCheck!!
            val started = shadowOf(main.get()).nextStartedActivityForResult
            assertEquals(GuidedScanActivity::class.java.name, started.intent.component!!.className)
            assertEquals(
                pending.faces,
                started.intent
                    .getStringArrayExtra(QuickCheckContract.EXTRA_FACES)!!
                    .map(Face::valueOf)
                    .toSet(),
            )
            val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, started.intent).setup()
            try {
                rule.waitForIdle()
                val vm = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
                rule.waitUntil(10_000) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    source.starts > 0
                }
                val actual = original.apply(FaceTurn(Face.R, Turn.COUNTERCLOCKWISE))
                Face.entries.filter { it in pending.faces }.forEach { face ->
                    assertEquals(GuidedScanPlan.reprompt(face), vm.controller.step)
                    val colors = actual.toFacelets().colors.subList(face.ordinal * 9, face.ordinal * 9 + 9)
                    source.feed(FrameStats(colors.map { CellReading(labs.getValue(it), true) }, 120.0, 0.0, 100.0, 12), null)
                    rule.waitForIdle()
                    vm.frozen?.let { vm.endFreeze(it.id) }
                }
                val result = shadowOf(scan.get())
                assertEquals(android.app.Activity.RESULT_OK, result.resultCode)
                ShadowLog.clear()
                shadowOf(main.get()).receiveResult(started.intent, result.resultCode, result.resultIntent)
            } finally {
                scan.pause().stop().destroy()
            }
            rule.waitUntil(10_000) { host.state.value.pendingQuickCheck == null }
            rule.waitForIdle()
            rule.onNodeWithTag("certainty:label").assertTextEquals("Likely")
            rule.onNodeWithText("That turn probably moved something else.").assertExists()
            rule.onNodeWithTag("mismatch:scanAll").assertTextEquals("Scan all 6 faces")
            val records = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }.filter { it.startsWith("quickCheck run ") }
            assertEquals(1, records.size)
            val record =
                Regex(
                    """quickCheck run kind=Partial captures=[12] elapsedMs=\d+ certainty=LIKELY matches=false observed=[URFDLB]:[WYGBRO-]{9}(?:,[URFDLB]:[WYGBRO-]{9})? expected=[WYGBRO]{54} expectedHome=[WYGBRO]{54} device=[A-Za-z0-9-]+""",
                )
            assertTrue(records.single(), record.matches(records.single()))

            rule.onNodeWithTag("mismatch:scanAll").performScrollTo().performClick()
            awaitStarted(main)
            val full = shadowOf(main.get()).nextStartedActivityForResult
            assertTrue(full.intent.getBooleanExtra(QuickCheckContract.EXTRA_FULL_QUICK_CHECK, false))
            val priorStarts = source.starts
            val fullScan = Robolectric.buildActivity(GuidedScanActivity::class.java, full.intent).setup()
            try {
                rule.waitUntil(10_000) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    source.starts > priorStarts
                }
                val vm = ViewModelProvider(fullScan.get())[GuidedScanViewModel::class.java]
                val actual =
                    host.state.value.expected
                        .reoriented(dev.cubentia.domain.cube.Orientation.HOME)
                // Follow any named-top re-prompts that the full scan needs to resolve the face turns.
                repeat(2 * Face.entries.size) {
                    val step = vm.controller.step ?: return@repeat
                    val colors = actual.toFacelets().colors.subList(step.face.ordinal * 9, step.face.ordinal * 9 + 9)
                    source.feed(FrameStats(colors.map { CellReading(labs.getValue(it), true) }, 120.0, 0.0, 100.0, 12), null)
                    rule.waitForIdle()
                    vm.frozen?.let { vm.endFreeze(it.id) }
                }
                assertTrue("Full scan ended in ${vm.phase}", vm.phase is dev.cubentia.app.camera.GuidedScanPhase.Review)
                ShadowLog.clear()
                rule.onNodeWithTag("review:accept").performClick()
                val result = shadowOf(fullScan.get())
                assertEquals(android.app.Activity.RESULT_OK, result.resultCode)
                shadowOf(main.get()).receiveResult(full.intent, result.resultCode, result.resultIntent)
            } finally {
                fullScan.pause().stop().destroy()
            }
            rule.waitUntil(10_000) { host.state.value.pendingQuickCheck == null }
            rule.waitForIdle()
            val fullRecords = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }.filter { it.startsWith("quickCheck run ") }
            assertTrue(
                fullRecords.single(),
                Regex(
                    """quickCheck run kind=Full captures=\d+ elapsedMs=\d+ certainty=VERIFIED matches=true observed=[WYGBRO]{54} .*""",
                ).matches(fullRecords.single()),
            )
        } finally {
            main.pause().stop().destroy()
        }
    }

    /** Opens lesson-01-01 from the real Learn tab and switches it to physical mode (awaiting confirmation). */
    private fun physicalLesson(main: org.robolectric.android.controller.ActivityController<MainActivity>) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("welcome:imNew").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("welcome:imNew").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("learn:lesson:lesson-01-01").performScrollTo().performClick()
        rule.waitUntil(10_000) {
            host.state.value.lesson
                ?.id
                ?.value == "lesson-01-01"
        }
        rule.waitForIdle()
        rule.onNodeWithTag("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(10_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        checkNotNull(main.get())
    }

    private fun awaitStarted(main: org.robolectric.android.controller.ActivityController<MainActivity>) {
        rule.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            shadowOf(main.get()).peekNextStartedActivityForResult() != null
        }
    }

    @Test fun confirmCardScanItEntersTheScannedCube() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            physicalLesson(main)
            val scanned =
                host.state.value.expected
                    .apply(FaceTurn(Face.R, Turn.CLOCKWISE))
            rule.onNodeWithTag("confirmCube:scan").performScrollTo().performClick()
            awaitStarted(main)
            val started = shadowOf(main.get()).nextStartedActivityForResult
            assertEquals(GuidedScanActivity::class.java.name, started.intent.component!!.className)
            assertFalse(started.intent.hasExtra(QuickCheckContract.EXTRA_FACES))
            val (code, data) =
                dev.cubentia.app.scan.ScanFlowContract
                    .encode(
                        dev.cubentia.app.scan.ScanOutcome
                            .Accepted(scanned),
                    )
            shadowOf(main.get()).receiveResult(started.intent, code, data)
            rule.waitUntil(10_000) { host.state.value.syncState == SyncState.PHYSICAL_VERIFIED }
            assertEquals(scanned, host.state.value.expected)
            assertEquals(scanned, host.state.value.virtual)
        } finally {
            main.pause().stop().destroy()
        }
    }

    @Test fun uncalibratedRunReusesTheStartScanColorsForASubsetQuickCheck() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            physicalLesson(main)
            val scanned = host.state.value.expected
            rule.onNodeWithTag("confirmCube:scan").performScrollTo().performClick()
            awaitStarted(main)
            val confirm = shadowOf(main.get()).nextStartedActivityForResult
            assertTrue(confirm.intent.getBooleanExtra(QuickCheckContract.EXTRA_FULL_QUICK_CHECK, false))
            val startColors =
                dev.cubentia.domain.recognition
                    .CalibrationCenters(labs)
            val (code, data) =
                dev.cubentia.app.scan.ScanFlowContract
                    .encode(
                        dev.cubentia.app.scan.ScanOutcome
                            .Accepted(scanned, startColors),
                    )
            shadowOf(main.get()).receiveResult(confirm.intent, code, data)
            rule.waitUntil(10_000) { host.state.value.syncState == SyncState.PHYSICAL_VERIFIED }
            assertEquals(startColors, host.runScanColors())
            runBlocking { host.dispatch { SessionEvent.PhysicalConfirmed(FaceTurn(Face.R, Turn.CLOCKWISE), it) } }
            rule.waitForIdle()
            rule.onNodeWithTag("lesson:overflowMenu").performScrollTo().performClick()
            rule.onNodeWithTag("lesson:menuMismatch").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("lesson:quickCheck").performScrollTo().performClick()
            awaitStarted(main)
            val pending = host.state.value.pendingQuickCheck!!
            assertTrue(pending.faces.size < Face.entries.size)
            val started = shadowOf(main.get()).nextStartedActivityForResult
            // No saved calibration: the subset intent carries the run's start-scan colors.
            assertEquals(
                pending.faces,
                started.intent
                    .getStringArrayExtra(QuickCheckContract.EXTRA_FACES)!!
                    .map(Face::valueOf)
                    .toSet(),
            )
            assertNotNull(started.intent.getDoubleArrayExtra(QuickCheckContract.EXTRA_RUN_SCAN_COLORS))
            val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, started.intent).setup()
            try {
                rule.waitForIdle()
                val vm = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
                rule.waitUntil(10_000) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    source.starts > 0
                }
                assertEquals(pending.faces, vm.quickFaces)
                val actual = host.state.value.expected
                Face.entries.filter { it in pending.faces }.forEach { face ->
                    assertEquals(GuidedScanPlan.reprompt(face), vm.controller.step)
                    val colors = actual.toFacelets().colors.subList(face.ordinal * 9, face.ordinal * 9 + 9)
                    source.feed(FrameStats(colors.map { CellReading(labs.getValue(it), true) }, 120.0, 0.0, 100.0, 12), null)
                    rule.waitForIdle()
                    vm.frozen?.let { vm.endFreeze(it.id) }
                }
                val result = shadowOf(scan.get())
                assertEquals(android.app.Activity.RESULT_OK, result.resultCode)
                assertEquals(pending.faces, vm.quickResult!!.faces.keys)
                shadowOf(main.get()).receiveResult(started.intent, result.resultCode, result.resultIntent)
            } finally {
                scan.pause().stop().destroy()
            }
            rule.waitUntil(10_000) { host.state.value.pendingQuickCheck == null }
            assertEquals(
                dev.cubentia.domain.tutor.Certainty.VERIFIED,
                host.state.value.quickCheck
                    ?.certainty,
            )
            // Back to virtual ends the run's colors; the next physical run starts over with 6 faces.
            runBlocking { host.dispatch { SessionEvent.ModeSwitched(dev.cubentia.application.session.CubeMode.VIRTUAL, it) } }
            assertEquals(null, host.runScanColors())
        } finally {
            main.pause().stop().destroy()
        }
    }

    @Test fun uncalibratedRunQuickCheckUnderChangedLightRetakesEveryFaceAndNeverVerifies() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            physicalLesson(main)
            val scanned = host.state.value.expected
            rule.onNodeWithTag("confirmCube:scan").performScrollTo().performClick()
            awaitStarted(main)
            val confirm = shadowOf(main.get()).nextStartedActivityForResult
            val (code, data) =
                dev.cubentia.app.scan.ScanFlowContract
                    .encode(
                        dev.cubentia.app.scan.ScanOutcome
                            .Accepted(
                                scanned,
                                dev.cubentia.domain.recognition
                                    .CalibrationCenters(labs),
                            ),
                    )
            shadowOf(main.get()).receiveResult(confirm.intent, code, data)
            rule.waitUntil(10_000) { host.state.value.syncState == SyncState.PHYSICAL_VERIFIED }
            runBlocking { host.dispatch { SessionEvent.PhysicalConfirmed(FaceTurn(Face.R, Turn.CLOCKWISE), it) } }
            rule.waitForIdle()
            rule.onNodeWithTag("lesson:overflowMenu").performScrollTo().performClick()
            rule.onNodeWithTag("lesson:menuMismatch").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("lesson:quickCheck").performScrollTo().performClick()
            awaitStarted(main)
            val pending = host.state.value.pendingQuickCheck!!
            val started = shadowOf(main.get()).nextStartedActivityForResult
            assertNotNull(started.intent.getDoubleArrayExtra(QuickCheckContract.EXTRA_RUN_SCAN_COLORS))
            // The light changed after the start scan: every sticker is darker and yellower.
            val shifted = labs.mapValues { (_, lab) -> Lab(lab.l - 15.0, lab.a, lab.b + 20.0) }
            val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, started.intent).setup()
            try {
                rule.waitForIdle()
                val vm = ViewModelProvider(scan.get())[GuidedScanViewModel::class.java]
                rule.waitUntil(10_000) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    source.starts > 0
                }
                val actual = host.state.value.expected
                val asked = mutableListOf<Face>()
                repeat(4 * Face.entries.size) {
                    val step = vm.controller.step ?: return@repeat
                    asked += step.face
                    val colors = actual.toFacelets().colors.subList(step.face.ordinal * 9, step.face.ordinal * 9 + 9)
                    source.feed(FrameStats(colors.map { CellReading(shifted.getValue(it), true) }, 120.0, 0.0, 100.0, 12), null)
                    rule.waitForIdle()
                    vm.frozen?.let { vm.endFreeze(it.id) }
                }
                val planned = Face.entries.filter { it in pending.faces }
                // Each face asked once, then every captured face is doubtful and asked once more (up to the retake cap).
                assertEquals(planned, asked.take(planned.size))
                val retakes = asked.drop(planned.size)
                assertEquals(
                    minOf(planned.size, dev.cubentia.domain.recognition.ScanAssembler.MAX_REPROMPT_FACES),
                    retakes.size,
                )
                assertEquals(retakes.distinct(), retakes)
                assertTrue(planned.containsAll(retakes))
                val result = shadowOf(scan.get())
                assertEquals(android.app.Activity.RESULT_OK, result.resultCode)
                assertTrue(vm.quickResult!!.faces.isEmpty())
                shadowOf(main.get()).receiveResult(started.intent, result.resultCode, result.resultIntent)
            } finally {
                scan.pause().stop().destroy()
            }
            rule.waitUntil(10_000) { host.state.value.pendingQuickCheck == null }
            assertTrue(
                host.state.value.quickCheck
                    ?.certainty != dev.cubentia.domain.tutor.Certainty.VERIFIED,
            )
        } finally {
            main.pause().stop().destroy()
        }
    }

    @Test fun quickCheckResultReturningAfterRecreationStillApplies() {
        runBlocking {
            repository.save(
                dev.cubentia.domain.recognition
                    .CalibrationCenters(labs),
            )
        }
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            physicalLesson(main)
            rule.onNodeWithTag("confirmCube:matches").performScrollTo().performClick()
            rule.waitUntil(10_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
            rule.waitForIdle()
            rule.onNodeWithTag("lesson:overflowMenu").performScrollTo().performClick()
            rule.onNodeWithTag("lesson:menuMismatch").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("lesson:quickCheck").performScrollTo().performClick()
            awaitStarted(main)
            val pending = host.state.value.pendingQuickCheck!!
            val started = shadowOf(main.get()).nextStartedActivityForResult
            main.recreate()
            rule.waitForIdle()
            val expected =
                host.state.value.expected
                    .reoriented(dev.cubentia.domain.cube.Orientation.HOME)
            val colors = expected.toFacelets().colors
            val face = pending.faces.first()
            val (code, data) =
                QuickCheckContract.encode(
                    dev.cubentia.domain.recognition.PhysicalObservation
                        .Partial(mapOf(face to colors.subList(face.ordinal * 9, face.ordinal * 9 + 9))),
                )
            ShadowLog.clear()
            shadowOf(main.get()).receiveResult(started.intent, code, data)
            rule.waitUntil(10_000) { host.state.value.pendingQuickCheck == null }
            assertNotNull(host.state.value.quickCheck)
            rule.waitUntil(10_000) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                ShadowLog.getLogsForTag(ScanDiagnostics.TAG).any { it.msg.startsWith("quickCheck run ") }
            }
            val run = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }.single { it.startsWith("quickCheck run ") }
            assertTrue(run, run.startsWith("quickCheck run kind=Partial captures=- elapsedMs=- "))
        } finally {
            main.pause().stop().destroy()
        }
    }

    @Test fun cancelledQuickCheckClearsPendingAndCanStartAgain() {
        runBlocking {
            repository.save(
                dev.cubentia.domain.recognition
                    .CalibrationCenters(labs),
            )
        }
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            physicalLesson(main)
            rule.onNodeWithTag("confirmCube:matches").performScrollTo().performClick()
            rule.waitUntil(10_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
            rule.waitForIdle()
            rule.onNodeWithTag("lesson:overflowMenu").performScrollTo().performClick()
            rule.onNodeWithTag("lesson:menuMismatch").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("lesson:quickCheck").performScrollTo().performClick()
            awaitStarted(main)
            assertNotNull(host.state.value.pendingQuickCheck)
            val started = shadowOf(main.get()).nextStartedActivityForResult
            ShadowLog.clear()
            val scan = Robolectric.buildActivity(GuidedScanActivity::class.java, started.intent).setup()
            try {
                rule.waitForIdle()
                scan.get().onBackPressedDispatcher.onBackPressed()
                rule.waitForIdle()
                val result = shadowOf(scan.get())
                assertTrue(scan.get().isFinishing)
                assertEquals(android.app.Activity.RESULT_CANCELED, result.resultCode)
                shadowOf(main.get()).receiveResult(started.intent, result.resultCode, result.resultIntent)
            } finally {
                scan.pause().stop().destroy()
            }
            rule.waitUntil(10_000) { host.state.value.pendingQuickCheck == null }
            rule.waitForIdle()
            val records = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }.filter { it.startsWith("quickCheck run ") }
            assertTrue("kind=- captures=0 elapsedMs=- certainty=CANCELLED matches=- observed=- " in records.single())
            rule.onNodeWithTag("lesson:quickCheck").performScrollTo().performClick()
            awaitStarted(main)
            assertNotNull(host.state.value.pendingQuickCheck)
            assertEquals(
                GuidedScanActivity::class.java.name,
                shadowOf(main.get())
                    .nextStartedActivityForResult.intent.component!!
                    .className,
            )
        } finally {
            main.pause().stop().destroy()
        }
    }

    @Test fun confirmCardScanReturningAfterMatchesLeavesExpectedUnchanged() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            physicalLesson(main)
            val original = host.state.value.expected
            rule.onNodeWithTag("confirmCube:scan").performScrollTo().performClick()
            awaitStarted(main)
            val started = shadowOf(main.get()).nextStartedActivityForResult
            rule.onNodeWithTag("confirmCube:matches").performScrollTo().performClick()
            rule.waitUntil(10_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
            val (code, data) =
                dev.cubentia.app.scan.ScanFlowContract
                    .encode(
                        dev.cubentia.app.scan.ScanOutcome
                            .Accepted(original.apply(FaceTurn(Face.R, Turn.CLOCKWISE))),
                    )
            shadowOf(main.get()).receiveResult(started.intent, code, data)
            rule.waitForIdle()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(SyncState.PHYSICAL_PREDICTED, host.state.value.syncState)
            assertEquals(original, host.state.value.expected)
        } finally {
            main.pause().stop().destroy()
        }
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

                override fun quickCheckIntent(
                    context: Context,
                    faces: Set<Face>,
                ) = guidedScanIntent(
                    context,
                ).putExtra(QuickCheckContract.EXTRA_FACES, faces.map { it.name }.toTypedArray())

                override fun guidedScanIntent(context: Context) = Intent(context, GuidedScanActivity::class.java)
            }
    }

    companion object {
        private lateinit var source: FakeFrames

        @Volatile private var denied = false

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
