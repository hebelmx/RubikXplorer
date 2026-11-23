package dev.cubentia.app.camera.sticker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import dev.cubentia.app.camera.CameraModule
import dev.cubentia.app.camera.CameraScanLauncher
import dev.cubentia.app.camera.CapturedFaceObserver
import dev.cubentia.app.camera.CornerDetector
import dev.cubentia.app.camera.CornerFrame
import dev.cubentia.app.camera.CornerGuide
import dev.cubentia.app.camera.CornerLocator
import dev.cubentia.app.camera.CornerReader
import dev.cubentia.app.camera.GuidedScanActivity
import dev.cubentia.app.camera.GuidedScanPhase
import dev.cubentia.app.camera.GuidedScanViewModel
import dev.cubentia.app.camera.ScanFrame
import dev.cubentia.app.camera.ScanFrameSource
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.grid
import dev.cubentia.app.camera.toCornerFrame
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.CornerFixtureJson
import dev.cubentia.domain.recognition.CornerFixtureRecord
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.QuickScanClassification
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
import java.io.File
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import javax.imageio.ImageIO

/**
 * Story 10.23 (AC5), route level: a recorded `Fixtures/corner` Quick Scan replays through the real activity five ways (the
 * reader unbound, bound but off, on with a fake interpreter, with its model missing, and with inference throwing) and
 * reaches the same review every time: the same review stickers, `ScanResult` evidence and `FaceSample`s. Shadow lines
 * appear only in the run that is on; the failing runs log their error line and nothing else changes.
 *
 * The corner frames carry the records' measured cells; their crops are unwarped from the matching JPEGs at the corner
 * guide (CornerReader with keepCrops, as the camera source does when an observer is bound). Faces the scan hands over
 * are fed to the Guided grid from the same records.
 */
@HiltAndroidTest
@UninstallModules(CameraModule::class, ShadowStickerReaderModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp")
class ShadowReaderReplayTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val camera = ReplayFrames()

    @BindValue internal val frames: ScanFrameSource = camera

    @BindValue val launcher: ScanLauncher =
        object : ScanLauncher by CameraScanLauncher() {
            override fun hasCamera(context: Context) = true
        }

    /** The bound observer: forwards to the run's reader (none: bound but idle). */
    private val switching = SwitchingObserver()

    @BindValue internal val observer: CapturedFaceObserver = switching

    private val lines = CopyOnWriteArrayList<String>()

    @Before
    fun setup() {
        hilt.inject()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA)
    }

    private val group = File("../Fixtures/corner/lenovo-tb128xu_stla_cool-front")
    private val records: List<CornerFixtureRecord> =
        listOf(1, 2).map { CornerFixtureJson.decode(File(group, "corner-$it.json").readText()) }

    private fun stats(sample: FaceSample) =
        FrameStats(
            sample.stickers.mapIndexed { index, lab ->
                CellReading(
                    lab,
                    if (index ==
                        FaceSample.CENTER_INDEX
                    ) {
                        sample.centerPlain != false
                    } else {
                        true
                    },
                )
            },
            120.0,
            0.0,
            100.0,
            12,
        )

    /** The hold's corner frame: measured cells, and the three faces unwarped from the JPEG at the corner guide. */
    private fun cornerFrame(record: CornerFixtureRecord): CornerFrame {
        val frame = ImageIO.read(File(group, record.frame)).toCornerFrame()
        val guide = CornerGuide(grid(frame.width, frame.height, 0))
        val atGuide = CornerDetector { _, g -> CornerLocator.Location(g.quads(g.vertices()), 1.0) }
        val crops: List<YuvFrame?>? = CornerReader.read(frame, guide, atGuide, keepCrops = true).faceCrops
        checkNotNull(crops) { "keepCrops attaches the crops" }
        return CornerFrame(true, record.labeledSamples.map { stats(it.sample) }, 1.0, faceCrops = crops)
    }

    private data class Outcome(
        val stickers: List<dev.cubentia.domain.cube.Color?>,
        val evidence: String,
        val samples: List<FaceSample>,
        val quickSamples: List<FaceSample>,
    )

    private enum class Mode { UNBOUND, OFF, ON, MODEL_MISSING, INFERENCE_THROWS }

    private val confident =
        StickerInterpreter { _, output ->
            output[0].forEachIndexed { cell, probabilities ->
                probabilities.fill(0f)
                probabilities[cell % probabilities.size] = 1f
            }
        }

    private fun reader(mode: Mode): ShadowStickerReader? =
        when (mode) {
            Mode.UNBOUND -> {
                null
            }

            Mode.OFF -> {
                ShadowStickerReader({ false }, { confident }, { lines += it })
            }

            Mode.ON -> {
                ShadowStickerReader({ true }, { confident }, { lines += it })
            }

            Mode.MODEL_MISSING -> {
                ShadowStickerReader({ true }, { throw java.io.FileNotFoundException("sticker_reader.tflite") }, {
                    lines +=
                        it
                })
            }

            Mode.INFERENCE_THROWS -> {
                ShadowStickerReader({ true }, { StickerInterpreter { _, _ -> error("inference failed") } }, {
                    lines +=
                        it
                })
            }
        }

    private fun replay(mode: Mode): Pair<Outcome, List<String>> {
        lines.clear()
        switching.ended.set(0)
        val reader = reader(mode)
        switching.target = reader
        val intent =
            Intent(RuntimeEnvironment.getApplication(), GuidedScanActivity::class.java).putExtra(GuidedScanActivity.EXTRA_QUICK_SCAN, true)
        val controller = Robolectric.buildActivity(GuidedScanActivity::class.java, intent).setup()
        try {
            if (mode == Mode.UNBOUND) controller.get().capturedFaceObserver = Optional.empty()
            waitForCamera(corner = true)
            val model = ViewModelProvider(controller.get())[GuidedScanViewModel::class.java]
            records.forEach { record ->
                camera.feed(ScanFrame(stats(record.labeledSamples.first().sample), 0, corner = cornerFrame(record)))
                shutter(model)
            }
            reader?.awaitIdle()
            val quickSamples = checkNotNull(model.quickScan).captured
            // The hand-over asks the faces Kotlin could not keep, one by one: each from its own record.
            val samples = records.flatMap { record -> record.labeledSamples.map { it.sample } }
            repeat(GUIDED_LIMIT) {
                val asked = (model.phase as? GuidedScanPhase.Viewfinder)?.step ?: return@repeat
                waitForCamera(corner = false)
                val sample = samples.single { it.centerColor == asked.centerColor }
                camera.feed(ScanFrame(stats(sample), 0))
                shutter(model)
            }
            val review = model.phase as? GuidedScanPhase.Review ?: error("$mode never reached the review: ${model.phase}")
            return Outcome(review.review.stickers, review.review.evidence.toString(), review.samples, quickSamples) to lines.toList()
        } finally {
            val model = ViewModelProvider(controller.get())[GuidedScanViewModel::class.java]
            controller.pause().stop().destroy()
            // Story 10.23: the scan ends with its activity: the observer hears it, and no crop is left on the view model.
            assertEquals("$mode: onScanEnded on destroy", if (mode == Mode.UNBOUND) 0 else 1, switching.ended.get())
            assertEquals("$mode: lastCornerCrops after destroy", null, model.lastCornerCrops)
            switching.target = null
            reader?.awaitIdle()
        }
    }

    @Test
    fun theReviewIsTheSameWhetherTheReaderIsUnboundOffOnMissingOrFailing() {
        val runs = Mode.entries.associateWith(::replay)
        val reference = runs.getValue(Mode.UNBOUND).first
        assertTrue("the replay reaches a review with stickers", reference.stickers.any { it != null })
        runs.forEach { (mode, run) -> assertEquals("$mode changes the review", reference, run.first) }

        fun shadow(mode: Mode) = runs.getValue(mode).second.filter { it.startsWith("quickScan shadow ") }
        assertEquals(emptyList<String>(), runs.getValue(Mode.UNBOUND).second)
        assertEquals(listOf("quickScan shadowReader state=off"), runs.getValue(Mode.OFF).second)
        val on = shadow(Mode.ON)
        assertEquals("one line per face per capture", 6, on.size)
        assertTrue(
            on.toString(),
            on.all { Regex(""".* inferMs=\d+ model=[WYROBG]{9}/C{9} self=\S+ second=(cal|norm|-):?\S*""").matches(it) },
        )
        assertTrue(
            "the judged faces carry both Kotlin reads: $on",
            on.any { Regex(""".* self=[WYROBG-]{9}/[CU-]{9} second=(cal|norm):[WYROBG-]{9}/[CU-]{9}""").matches(it) },
        )
        assertEquals("quickScan shadowReader state=on", runs.getValue(Mode.ON).second.first())
        assertEquals(listOf(Mode.ON), Mode.entries.filter { shadow(it).isNotEmpty() })
        assertEquals(
            listOf("quickScan shadowReader state=on", "quickScan shadowReader error=model-missing"),
            runs.getValue(Mode.MODEL_MISSING).second,
        )
        assertEquals(
            listOf(
                "quickScan shadowReader state=on",
                "quickScan shadowReader error=inference hold=1",
                "quickScan shadowReader error=inference hold=2",
            ),
            runs.getValue(Mode.INFERENCE_THROWS).second,
        )
    }

    private fun shutter(model: GuidedScanViewModel) {
        rule.onNodeWithTag("scanCapture").performClick()
        model.analyzerExecutor.submit {}.get()
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        rule.runOnIdle { model.frozen?.let { model.endFreeze(it.id) } }
        rule.waitForIdle()
    }

    private fun waitForCamera(corner: Boolean) {
        rule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            camera.receiver != null && camera.corner == corner
        }
    }

    private class SwitchingObserver : CapturedFaceObserver {
        @Volatile var target: CapturedFaceObserver? = null
        val ended =
            java.util.concurrent.atomic
                .AtomicInteger()

        override fun onScanStarted(scan: Long) {
            target?.onScanStarted(scan)
        }

        override fun onCornerCaptured(
            hold: Int,
            crops: List<YuvFrame?>,
            reports: List<QuickScanClassification.FaceReport>,
        ) {
            target?.onCornerCaptured(hold, crops, reports)
        }

        override fun onScanEnded(scan: Long) {
            ended.incrementAndGet()
            target?.onScanEnded(scan)
        }
    }

    private class ReplayFrames : ScanFrameSource {
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

    private companion object {
        const val GUIDED_LIMIT = 12
    }
}
