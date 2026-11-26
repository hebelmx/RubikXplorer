package dev.cubentia.app.camera.recorder

import dev.cubentia.app.camera.ContinuousCaptureStatus
import dev.cubentia.app.camera.ContinuousFrame
import dev.cubentia.app.camera.ContinuousFrameGeometry
import dev.cubentia.app.camera.ContinuousFrameTap
import dev.cubentia.app.camera.ContinuousLatencyBaseline
import dev.cubentia.app.camera.ContinuousScanEngine
import dev.cubentia.app.camera.ContinuousScanEngineFactory
import dev.cubentia.app.camera.ContinuousSessionCapture
import dev.cubentia.app.camera.ContinuousSessionSaved
import dev.cubentia.app.camera.ContinuousSessionSetup
import dev.cubentia.app.camera.ContinuousSessionWriterFactory
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.YuvPlane
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.recognition.StickerTruth
import dev.cubentia.domain.recognition.TruthSource
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.QuadObservation
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFormat
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionJson
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionLabels
import dev.cubentia.domain.recognition.continuous.session.SessionCountCheck
import dev.cubentia.domain.recognition.continuous.session.SessionOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime
import java.util.Optional
import java.util.concurrent.Executor

/** Story 10.34: the continuous recorder's controls, run with inline executors and fake engine and writer. */
class ContinuousRecorderControllerTest {
    @TempDir lateinit var dir: File

    private val inline = Executor { it.run() }

    /** A complete export whose canonical facelets differ from the (empty) mapper net. */
    private val state: CubeState = CubeState.SOLVED.apply((Notation.parse("R U F'") as NotationResult.Parsed).moves)
    private val complete = ContinuousScanExport.Complete(state, state.toFacelets(), ContinuousScanHandoff(emptyMap()))

    private class Engine(
        val export: ContinuousScanExport,
    ) : ContinuousScanEngine {
        override fun processFrame(
            frame: YuvFrame,
            rotationDegrees: Int,
            viewWidth: Int,
            viewHeight: Int,
        ) = ContinuousFrame(
            ContinuousScanSnapshot.Scanning(
                0,
                emptyMap(),
                emptyList(),
                emptyList(),
                false,
                ContinuousScanCue.BringIntoView,
                0,
                0,
                0,
                0,
            ),
            emptyList(),
        )

        override fun reset() = Unit

        override fun export() = export
    }

    private inner class Capture : ContinuousSessionCapture {
        var saved: ContinuousScanExport? = null
        var discarded = false

        override val status get() = ContinuousCaptureStatus(saved == null && !discarded, 0, 0, 0, 0, null)

        override fun wantsModelInput(frameIndex: Int) = false

        override fun onFrame(
            frameIndex: Int,
            observations: List<QuadObservation>,
            snapshot: ContinuousScanSnapshot,
            detection: StickerCenterDetection,
            modelInput: ByteArray?,
        ) = Unit

        override fun frameDone(
            timestampMs: Long,
            analyzerMs: Double,
            geometry: ContinuousFrameGeometry,
        ) = Unit

        override fun stop() = Unit

        override fun save(export: ContinuousScanExport): ContinuousSessionSaved {
            saved = export
            return ContinuousSessionSaved("s1", SessionOutcome.COMPLETE, emptyList(), dir.path)
        }

        override fun discard(): Boolean {
            discarded = true
            return true
        }
    }

    private inner class Writers : ContinuousSessionWriterFactory {
        val setups = mutableListOf<ContinuousSessionSetup>()
        val captures = mutableListOf<Capture>()

        override fun create(setup: ContinuousSessionSetup): ContinuousSessionCapture {
            setups += setup
            return Capture().also { captures += it }
        }

        val baselines = mutableListOf<Pair<String, List<Double>>>()

        override fun saveBaseline(
            deviceModel: String,
            analyzerMs: List<Double>,
        ): ContinuousLatencyBaseline? {
            baselines += deviceModel to analyzerMs
            val sorted = analyzerMs.sorted()
            return sorted.takeIf { it.isNotEmpty() }?.let { ContinuousLatencyBaseline(deviceModel, it.size, it[it.size / 2], it.last()) }
        }
    }

    private fun controller(
        writers: Writers,
        analyzer: ContinuousRecorderAnalyzer =
            ContinuousRecorderAnalyzer(
                Optional.empty(),
                Optional.of(
                    ContinuousScanEngineFactory { _: ContinuousFrameTap? ->
                        Engine(complete)
                    },
                ),
            ),
    ): ContinuousRecorderController =
        ContinuousRecorderController(analyzer, writers, null, "TB128XU", "LENOVO", inline, inline, inline) {
            LocalDateTime.of(2026, 11, 26, 9, 0, 0)
        }

    @Test
    fun `start waits for the meta, adb commands wait for the open screen`() {
        val writers = Writers()
        val c = controller(writers)
        c.start()
        assertEquals(ContinuousPhase.IDLE, c.state.value.phase)
        assertEquals("Fill in the cube and light before Start.", c.state.value.statusLine)
        c.meta = ContinuousSessionMeta("stla", "studio")
        c.command("start", "run1")
        assertTrue(writers.setups.isEmpty())
        c.open = true
        c.command("start", "run1")
        assertEquals(ContinuousPhase.RECORDING, c.state.value.phase)
        assertEquals("20261126-090000-TB128XU-run1", writers.setups.single().sessionId)
    }

    @Test
    fun `save hands the engine export to the session and pre-fills the canonical complete net`() {
        val writers = Writers()
        val c = controller(writers)
        c.meta = ContinuousSessionMeta("stla", "studio")
        c.start()
        c.save()
        assertSame(complete, writers.captures.single().saved)
        val ui = c.state.value
        assertEquals(ContinuousPhase.SAVED, ui.phase)
        assertEquals("Saved s1 · COMPLETE", ui.statusLine)
        assertEquals(complete.facelets.colors, ui.lastNet)
        val labels = c.saveLabels(ui.lastNet.map { StickerTruth(it, TruthSource.PREFILLED, true) }, null)
        assertEquals(SessionCountCheck.PASSED, labels.countCheck)
        assertEquals(labels, ContinuousSessionJson.readLabels(dir))
    }

    @Test
    fun `discard ends idle and says so`() {
        val writers = Writers()
        val c = controller(writers)
        c.meta = ContinuousSessionMeta("stla", "studio")
        c.start()
        c.stop()
        assertEquals(ContinuousPhase.STOPPED, c.state.value.phase)
        c.discard()
        assertTrue(writers.captures.single().discarded)
        assertEquals(ContinuousPhase.IDLE, c.state.value.phase)
        assertEquals("Discarded.", c.state.value.statusLine)
    }

    @Test
    fun `a baseline run saves the analyzer samples for the device and shows p50 and p95`() {
        val writers = Writers()
        // Analyzed frames take 4, 6, 4 and 6 ms (nanoTime is read before and after each frame).
        val clock = listOf(0, 4, 10, 16, 20, 24, 30, 36).map { it * 1_000_000L }.iterator()
        val analyzer =
            ContinuousRecorderAnalyzer(
                Optional.empty(),
                Optional.of(ContinuousScanEngineFactory { _: ContinuousFrameTap? -> Engine(complete) }),
            ) { clock.next() }
        val c = controller(writers, analyzer)
        c.baseline()
        assertEquals(ContinuousPhase.BASELINE, c.state.value.phase)
        assertTrue(analyzer.baselineRunning)
        val y = java.nio.ByteBuffer.allocate(64 * 48)
        val uv = java.nio.ByteBuffer.allocate(32 * 24)
        val yuv = YuvFrame(YuvPlane(y, 64, 1), YuvPlane(uv, 32, 1), YuvPlane(uv, 32, 1), 0, 0, 64, 48)
        val geometry = ContinuousFrameGeometry(0, 64, 48, 0, 0, 64, 48)
        var timestampMs = 0L
        var frames = 0
        while (analyzer.baselineRunning) {
            analyzer.analyze(RecorderMode.CONTINUOUS, yuv, null, 0, 64, 48, timestampMs, geometry)
            c.onAnalyzed(timestampMs)
            timestampMs += 10_000L
            frames++
        }
        assertEquals(4, frames)
        val (device, samples) = writers.baselines.single()
        assertEquals("TB128XU", device)
        assertEquals(listOf(4.0, 6.0, 4.0, 6.0), samples)
        val ui = c.state.value
        assertEquals(ContinuousPhase.IDLE, ui.phase)
        assertEquals("Baseline TB128XU: p50 6.0 ms · p95 6.0 ms · 4 frames", ui.statusLine)
        assertTrue(writers.setups.isEmpty())
    }

    @Test
    fun `writeLabels writes decodable labels and replaces existing ones`() {
        val first = ContinuousSessionLabels.of(List(54) { StickerTruth(Color.entries[it / 9], TruthSource.ENTERED, true) }, null)
        val second = ContinuousSessionLabels.of(List(54) { StickerTruth(null, null, false) }, "dark room")
        ContinuousRecorderController.writeLabels(dir, first)
        assertEquals(first, ContinuousSessionJson.readLabels(dir))
        ContinuousRecorderController.writeLabels(dir, second)
        assertEquals(second, ContinuousSessionJson.readLabels(dir))
        assertEquals(SessionCountCheck.OVERRIDDEN, ContinuousSessionJson.readLabels(dir)?.countCheck)
        assertFalse(File(dir, ContinuousSessionFormat.LABELS + ".tmp").exists())
    }
}
