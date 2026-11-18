package dev.cubentia.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import dev.cubentia.app.scan.CaseCaptureContract
import dev.cubentia.app.scan.CaseCaptureOutcome
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.application.port.CalibrationLoad
import dev.cubentia.application.port.CalibrationRepository
import dev.cubentia.application.port.CalibrationWrite
import dev.cubentia.application.usecase.UpdateCalibration
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CalibrationSession
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.GuidedScanPlan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class CaseRecognitionRouteTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Inject lateinit var updateCalibration: UpdateCalibration

    @Inject lateinit var repository: CalibrationRepository
    private val camera = FakeCamera()

    @BindValue internal val frames: ScanFrameSource = camera

    @BindValue val launcher: ScanLauncher =
        object : ScanLauncher by CameraScanLauncher() {
            override fun hasCamera(context: Context) = true

            override fun cameraDenied(context: Context) = denied
        }

    private var denied = false

    @Test
    fun libraryRunsCalibrationAndCapturesBothCornersBeforeReturningTheResult() {
        fromLibrary { intent, returnResult ->
            assertEquals(2, intent.getIntExtra(GuidedScanActivity.EXTRA_CASE_CAPTURE, 0))
            val activity = Robolectric.buildActivity(GuidedScanActivity::class.java, intent).setup()
            try {
                waitFor("calibration:prompt")
                assertEquals(false, camera.corner)
                rule.onNodeWithTag("quickScan:counter").assertDoesNotExist()
                val vm = ViewModelProvider(activity.get())[GuidedScanViewModel::class.java]
                rule.runOnIdle {
                    var session = CalibrationSession()
                    GuidedScanPlan.steps.forEach { step ->
                        val lab = ScanRouteTest.labs.getValue(step.centerColor)
                        session = session.offer(CellReading(lab, true)).keep()
                    }
                    vm.phase = (vm.phase as GuidedScanPhase.Calibration).copy(session = session)
                }
                rule.onNodeWithTag("calibration:save").performClick()
                waitFor("quickScan:counter")
                rule.waitUntil(10_000) { camera.corner && camera.receiver != null }
                assertEquals(true, runBlocking { repository.load() } is CalibrationLoad.Loaded)
                rule.onNodeWithTag("quickScan:counter").assertTextEquals("1 / 2")
                rule.onNodeWithText("Hold yellow on top, with one corner facing you.").assertIsDisplayed()
                rule.onNodeWithTag("scanNet").assertDoesNotExist()
                camera.feed(Color.YELLOW, Color.GREEN, Color.ORANGE)
                shutter(vm)
                rule.onNodeWithTag("quickScan:counter").assertTextEquals("2 / 2")
                rule.onNodeWithText("Now turn the cube halfway, yellow still on top, so the opposite corner faces you.").assertIsDisplayed()
                camera.feed(Color.YELLOW, Color.GREEN, Color.ORANGE)
                shutter(vm)
                rule
                    .onNodeWithText(
                        "That looks like the same corner — turn the cube halfway so the opposite corner faces you.",
                    ).assertIsDisplayed()
                camera.feed(Color.YELLOW, Color.BLUE, Color.RED)
                shutter(vm)
                rule.waitUntil(10_000) { activity.get().isFinishing }
                val scan = shadowOf(activity.get())
                val outcome = CaseCaptureContract.decode(scan.resultCode, scan.resultIntent) as CaseCaptureOutcome.Captured
                assertEquals(2, outcome.views.size)
                returnResult(scan.resultCode, scan.resultIntent)
            } finally {
                activity.pause().stop().destroy()
            }
            rule.onNodeWithText("Your last layer is already solved.").assertExists()
        }
    }

    @Test
    fun closingUnsavedCalibrationCancelsAndReturnsToLibrary() {
        fromLibrary { intent, returnResult ->
            val activity = Robolectric.buildActivity(GuidedScanActivity::class.java, intent).setup()
            try {
                waitFor("calibration:prompt")
                rule.onNodeWithTag("scanClose").performClick()
                val scan = shadowOf(activity.get())
                assertEquals(CaseCaptureOutcome.Cancelled, CaseCaptureContract.decode(scan.resultCode, scan.resultIntent))
                returnResult(scan.resultCode, scan.resultIntent)
            } finally {
                activity.pause().stop().destroy()
            }
            rule.onNodeWithTag("library:recognize").assertExists()
        }
    }

    @Test
    fun savedCalibrationOpensASingleExtraHoldWithoutACounterAndOffersTheEditor() {
        hilt.inject()
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
        assertEquals(CalibrationWrite.Saved, runBlocking { updateCalibration(CalibrationCenters(ScanRouteTest.labs)) })
        val activity = Robolectric.buildActivity(GuidedScanActivity::class.java, launcher.caseCaptureIntent(context, 1)).setup()
        try {
            waitFor("quickScan:outline")
            rule.onNodeWithTag("calibration:prompt").assertDoesNotExist()
            rule.onNodeWithTag("quickScan:counter").assertDoesNotExist()
            rule.onNodeWithText("Hold yellow on top, with one corner facing you.").assertIsDisplayed()
            rule.onNodeWithTag("scanEnterColors").performClick()
            val scan = shadowOf(activity.get())
            assertEquals(CaseCaptureOutcome.FellBackToEditor, CaseCaptureContract.decode(scan.resultCode, scan.resultIntent))
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun recordedDenialFromLibraryOpensTheRecognizeEditorAndStartsNoScan() {
        denied = true
        hilt.inject()
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            waitFor("welcome:imNew")
            rule.onNodeWithTag("welcome:imNew").performClick()
            rule.onNodeWithTag("nav:library").performClick()
            rule.onNodeWithTag("library:recognize").performClick()
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                rule.onAllNodesWithText("Camera access is off, so enter your cube's colors here.").fetchSemanticsNodes().isNotEmpty()
            }
            assertNull(shadowOf(main.get()).peekNextStartedActivityForResult())
        } finally {
            main.pause().stop().destroy()
        }
    }

    private fun fromLibrary(block: (Intent, (Int, Intent?) -> Unit) -> Unit) {
        hilt.inject()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            waitFor("welcome:imNew")
            rule.onNodeWithTag("welcome:imNew").performClick()
            rule.onNodeWithTag("nav:library").performClick()
            rule.onNodeWithTag("library:recognize").performClick()
            val activity = shadowOf(main.get())
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                activity.peekNextStartedActivityForResult() != null
            }
            val intent = activity.nextStartedActivityForResult.intent
            assertEquals(GuidedScanActivity::class.java.name, intent.component?.className)
            block(intent) { code, data -> activity.receiveResult(intent, code, data) }
        } finally {
            main.pause().stop().destroy()
        }
    }

    private fun waitFor(tag: String) {
        rule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun shutter(model: GuidedScanViewModel) {
        rule.onNodeWithTag("scanCapture").performClick()
        model.analyzerExecutor.submit {}.get()
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
    }

    private class FakeCamera : ScanFrameSource {
        @Volatile var receiver: ((ScanFrame) -> Unit)? = null

        @Volatile var corner = false
        private lateinit var executor: ExecutorService

        override fun preview(context: Context) = View(context)

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

        fun feed(vararg colors: Color) {
            assertNotNull(receiver)
            val faces =
                colors.map { color ->
                    FrameStats(List(9) { CellReading(ScanRouteTest.labs.getValue(color), true) }, 120.0, 0.0, 100.0, 12)
                }
            executor.submit { checkNotNull(receiver)(ScanFrame(faces.first(), 0, corner = CornerFrame(true, faces))) }.get()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
