package dev.cubentia.app.camera.recorder

import dev.cubentia.app.camera.ContinuousCaptureStatus
import dev.cubentia.app.camera.ContinuousFrame
import dev.cubentia.app.camera.ContinuousFrameGeometry
import dev.cubentia.app.camera.ContinuousFrameTap
import dev.cubentia.app.camera.ContinuousScanEngine
import dev.cubentia.app.camera.ContinuousScanEngineFactory
import dev.cubentia.app.camera.ContinuousSessionCapture
import dev.cubentia.app.camera.ContinuousSessionSaved
import dev.cubentia.app.camera.CornerDetector
import dev.cubentia.app.camera.CornerGuide
import dev.cubentia.app.camera.GridSquare
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.YuvPlane
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.QuadObservation
import dev.cubentia.domain.recognition.continuous.session.SessionOutcome
import dev.cubentia.domain.recognition.continuous.session.SessionStopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.Optional

/** Story 10.34: the recorder's per-frame branch: continuous frames reach only the continuous engine. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ContinuousRecorderAnalyzerTest {
    private val frame =
        ByteBuffer.allocate(64 * 48).let { y ->
            val uv = ByteBuffer.allocate(32 * 24)
            YuvFrame(y = YuvPlane(y, 64, 1), u = YuvPlane(uv, 32, 1), v = YuvPlane(uv, 32, 1), left = 0, top = 0, right = 64, bottom = 48)
        }
    private val geometry = ContinuousFrameGeometry(90, 64, 48, 0, 0, 64, 48)
    private val guide = CornerGuide(GridSquare(8.0, 4.0, 40.0))

    /** A corner detector that fails the test on any call. */
    private val failingCorner = Optional.of(CornerDetector { _, _ -> error("the CornerDetector provider must not be called") })

    private fun snapshot(index: Int) =
        ContinuousScanSnapshot.Scanning(
            index,
            emptyMap(),
            emptyList(),
            emptyList(),
            false,
            ContinuousScanCue.BringIntoView,
            0,
            index % 4,
            0,
            0,
        )

    private class FakeEngine : ContinuousScanEngine {
        val frames = mutableListOf<Int>()
        var exports = 0

        override fun processFrame(
            frame: YuvFrame,
            rotationDegrees: Int,
            viewWidth: Int,
            viewHeight: Int,
        ): ContinuousFrame {
            frames += rotationDegrees
            return ContinuousFrame(
                ContinuousScanSnapshot.Scanning(
                    frames.size - 1,
                    emptyMap(),
                    emptyList(),
                    emptyList(),
                    false,
                    ContinuousScanCue.BringIntoView,
                    0,
                    2,
                    0,
                    0,
                ),
                emptyList(),
            )
        }

        override fun reset() = Unit

        override fun export(): ContinuousScanExport {
            exports++
            return ContinuousScanExport.Partial(ContinuousScanHandoff(emptyMap()))
        }
    }

    private class FakeFactory : ContinuousScanEngineFactory {
        val engines = mutableListOf<FakeEngine>()
        val taps = mutableListOf<ContinuousFrameTap?>()

        override fun create(tap: ContinuousFrameTap?): ContinuousScanEngine {
            taps += tap
            return FakeEngine().also { engines += it }
        }
    }

    private class FakeCapture(
        var recordingNow: Boolean = true,
    ) : ContinuousSessionCapture {
        val done = mutableListOf<Pair<Long, Double>>()
        var stops = 0

        override val status get() =
            ContinuousCaptureStatus(
                recordingNow,
                done.size,
                0,
                0,
                0,
                if (recordingNow) null else SessionStopReason.FRAME_CAP,
            )

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
        ) {
            done += timestampMs to analyzerMs
        }

        override fun stop() {
            stops++
            recordingNow = false
        }

        override fun save(export: ContinuousScanExport) = ContinuousSessionSaved("id", SessionOutcome.PARTIAL, emptyList(), "p")

        override fun discard() = true
    }

    private fun ticking(stepNanos: Long): () -> Long {
        var now = 0L
        return { now.also { now += stepNanos } }
    }

    @Test
    fun `continuous frames go through the injected engine and never touch the corner detector`() {
        val factory = FakeFactory()
        val analyzer = ContinuousRecorderAnalyzer(failingCorner, Optional.of(factory), ticking(4_500_000))
        val capture = FakeCapture()
        assertTrue(analyzer.continuousAvailable)
        analyzer.startSession(capture)
        assertSame(capture, factory.taps.single())
        repeat(5) { i ->
            val result = analyzer.analyze(RecorderMode.CONTINUOUS, frame, guide, 90, 1080, 1920, 1000L + i * 33, geometry)
            result as RecorderFrame.Continuous
            assertNotNull(result.frame)
            assertEquals(4.5, result.analyzerMs, 1e-9)
        }
        assertEquals(List(5) { 90 }, factory.engines.single().frames)
        assertEquals(List(5) { (1000L + it * 33) to 4.5 }, capture.done)
        assertTrue(analyzer.recording)
        assertNotNull(analyzer.stop())
        assertEquals(1, capture.stops)
        assertFalse(analyzer.recording)
    }

    @Test
    fun `without a session or an engine binding the continuous branch does nothing`() {
        val idle = ContinuousRecorderAnalyzer(failingCorner, Optional.of(FakeFactory()))
        assertNull((idle.analyze(RecorderMode.CONTINUOUS, frame, null, 0, 0, 0, 0, geometry) as RecorderFrame.Continuous).frame)
        val none = ContinuousRecorderAnalyzer(failingCorner, Optional.empty())
        assertFalse(none.continuousAvailable)
        assertNull((none.analyze(RecorderMode.CONTINUOUS, frame, null, 0, 0, 0, 0, geometry) as RecorderFrame.Continuous).frame)
    }

    @Test
    fun `corner mode still reads with the CornerDetector provider and face mode reads neither`() {
        var cornerCalls = 0
        val corner = Optional.of(CornerDetector { _, _ -> null.also { cornerCalls++ } })
        val factory = FakeFactory()
        val analyzer = ContinuousRecorderAnalyzer(corner, Optional.of(factory))
        val read = analyzer.analyze(RecorderMode.CORNER, frame, guide, 0, 0, 0, 0, geometry) as RecorderFrame.Corner
        assertEquals(1, cornerCalls)
        assertFalse(checkNotNull(read.frame).detected)
        assertNull((analyzer.analyze(RecorderMode.CORNER, frame, null, 0, 0, 0, 0, geometry) as RecorderFrame.Corner).frame)
        assertEquals(RecorderFrame.Face, analyzer.analyze(RecorderMode.FACE, frame, guide, 0, 0, 0, 0, geometry))
        assertEquals(1, cornerCalls)
        assertTrue(factory.engines.isEmpty())
        // Without a provider the corner branch falls back to the classic locator, as before.
        val classic = ContinuousRecorderAnalyzer(Optional.empty(), Optional.of(factory))
        assertNotNull((classic.analyze(RecorderMode.CORNER, frame, guide, 0, 0, 0, 0, geometry) as RecorderFrame.Corner).frame)
    }

    @Test
    fun `an automatic stop by the writer stops the engine and keeps its export for Save`() {
        val factory = FakeFactory()
        val analyzer = ContinuousRecorderAnalyzer(failingCorner, Optional.of(factory))
        val capture = FakeCapture()
        analyzer.startSession(capture)
        analyzer.analyze(RecorderMode.CONTINUOUS, frame, null, 0, 0, 0, 0, geometry)
        capture.recordingNow = false
        analyzer.analyze(RecorderMode.CONTINUOUS, frame, null, 0, 0, 0, 33, geometry)
        assertFalse(analyzer.recording)
        val engine = factory.engines.single()
        assertEquals(2, engine.frames.size)
        // Frames after the stop are not processed; Save still gets the export.
        analyzer.analyze(RecorderMode.CONTINUOUS, frame, null, 0, 0, 0, 66, geometry)
        assertEquals(2, engine.frames.size)
        assertNotNull(analyzer.stop())
        assertEquals(1, engine.exports)
        analyzer.clear()
        assertNull(analyzer.stop())
    }

    @Test
    fun `a baseline run measures the engine without a tap for 30 s`() {
        val factory = FakeFactory()
        val analyzer = ContinuousRecorderAnalyzer(failingCorner, Optional.of(factory), ticking(2_000_000))
        analyzer.startBaseline()
        assertNull(factory.taps.single())
        assertTrue(analyzer.baselineRunning)
        listOf(0L, 10_000L, 29_999L).forEach { analyzer.analyze(RecorderMode.CONTINUOUS, frame, null, 0, 0, 0, it, geometry) }
        assertNull(analyzer.takeBaseline())
        analyzer.analyze(RecorderMode.CONTINUOUS, frame, null, 0, 0, 0, 30_000L, geometry)
        assertEquals(List(4) { 2.0 }, analyzer.takeBaseline())
        assertNull(analyzer.takeBaseline())
        assertFalse(analyzer.baselineRunning)
        // Stop ends a baseline early with what it measured.
        analyzer.startBaseline()
        analyzer.analyze(RecorderMode.CONTINUOUS, frame, null, 0, 0, 0, 0, geometry)
        analyzer.stop()
        assertEquals(listOf(2.0), analyzer.takeBaseline())
    }

    @Test
    fun `status lines follow the recorder copy`() {
        assertEquals(
            "Recording · frame 12/600 · 3.4 MB · skips 1 · sides 4/6",
            continuousStatusLine(ContinuousCaptureStatus(true, 12, 3_400_000, 1, 4, null)),
        )
        assertEquals(
            "Stopped · frame 600/600 · 12.0 MB · skips 0 · sides 6/6 · frame cap",
            continuousStatusLine(ContinuousCaptureStatus(false, 600, 12_000_000, 0, 6, SessionStopReason.FRAME_CAP)),
        )
        assertTrue(continuousStatusLine(ContinuousCaptureStatus(false, 9, 0, 0, 0, SessionStopReason.SIZE_CAP)).endsWith("size cap"))
        assertEquals("Saved s1 · COMPLETE", continuousSavedLine(ContinuousSessionSaved("s1", SessionOutcome.COMPLETE, emptyList(), "p")))
        assertEquals(
            "Saved s1 · PARTIAL · DEFECTIVE: captureSkips=2",
            continuousSavedLine(ContinuousSessionSaved("s1", SessionOutcome.PARTIAL, listOf("captureSkips=2"), "p")),
        )
        assertEquals(Face.entries.size, sessionPrefill(List(54) { null }).size)
    }
}
