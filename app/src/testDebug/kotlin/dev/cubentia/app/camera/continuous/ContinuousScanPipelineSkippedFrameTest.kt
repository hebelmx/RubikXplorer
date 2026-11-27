package dev.cubentia.app.camera.continuous

import dev.cubentia.app.camera.ContinuousFrameTap
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.StickerCenterDetector
import dev.cubentia.app.camera.StickerCenterPeak
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.YuvPlane
import dev.cubentia.domain.recognition.continuous.ContinuousFaceletMapper
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.FittedFace
import dev.cubentia.domain.recognition.continuous.Point2D
import dev.cubentia.domain.recognition.continuous.Quad2D
import dev.cubentia.domain.recognition.continuous.QuadObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * GH #403 I/O matrix: a frame the detector skipped ([StickerCenterDetection.skipped]) is a skipped frame, not an empty
 * view: the mapper never sees it, so three of them in a row neither grow its empty-frame streak nor clear tracking, and
 * the next good frame continues the master. A real empty view (good frame, no peaks) still reaches the mapper.
 */
class ContinuousScanPipelineSkippedFrameTest {
    private val face = faceFor(left = FACE_LEFT.toDouble(), top = FACE_TOP.toDouble(), size = FACE_SIZE.toDouble())

    private fun goodFrame(): YuvFrame =
        YuvFrame(
            y = YuvPlane(ByteBuffer.wrap(GOOD.y), WIDTH, 1),
            u = YuvPlane(ByteBuffer.wrap(GOOD.u), WIDTH / 2, 1),
            v = YuvPlane(ByteBuffer.wrap(GOOD.v), WIDTH / 2, 1),
            left = 0,
            top = 0,
            right = WIDTH,
            bottom = HEIGHT,
        )

    /** Returns the face's grid centers as peaks, except on [missFrames], where it returns [miss]. */
    private class ScriptedDetector(
        private val face: FittedFace,
        private val missFrames: Set<Int>,
        private val miss: StickerCenterDetection,
        /** Per-frame misses that override [miss], for runs that mix skipped frames and real empty views. */
        private val misses: Map<Int, StickerCenterDetection> = emptyMap(),
    ) : StickerCenterDetector {
        override val unavailable = false
        var calls = 0
        var failedFrames = 0

        override fun detect(
            frame: YuvFrame,
            rotationDegrees: Int,
            viewWidth: Int,
            viewHeight: Int,
        ): StickerCenterDetection {
            val index = calls++
            misses[index]?.let { return it }
            if (index in missFrames) return miss
            return StickerCenterDetection(
                peaks = face.gridCenters.map { p -> StickerCenterPeak(p.x, p.y, 0.95f, p.x / WIDTH, p.y / HEIGHT) },
                uprightWidth = WIDTH,
                uprightHeight = HEIGHT,
            )
        }

        override fun countFailedFrame() {
            failedFrames++
        }
    }

    private class RecordingTap : ContinuousFrameTap {
        val events = mutableListOf<String>()

        override fun modelInputBuffer(frameIndex: Int): ByteArray? = null

        override fun onFrame(
            frameIndex: Int,
            observations: List<QuadObservation>,
            snapshot: ContinuousScanSnapshot,
            detection: StickerCenterDetection,
            modelInputFilled: Boolean,
        ) {
            events += "frame $frameIndex"
        }

        override fun onSkippedFrame(frameIndex: Int) {
            events += "skipped $frameIndex"
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
        ) = Unit
    }

    private fun ContinuousScanPipeline.frame(index: Int) =
        processFrame(index, goodFrame(), rotationDegrees = 0, viewWidth = 480, viewHeight = 480)

    @Test
    fun `three skipped frames between good frames leave the mapper untouched and the next good frame continues the master`() {
        val mapper = ContinuousFaceletMapper()
        val detector = ScriptedDetector(face, missFrames = setOf(2, 3, 4), miss = StickerCenterDetection.SKIPPED)
        val tap = RecordingTap()
        val pipeline = ContinuousScanPipeline(detector, mapper = mapper).apply { this.tap = tap }

        pipeline.frame(0)
        val frame1 = pipeline.frame(1)
        assertTrue(frame1.snapshot.trackedQuads.isNotEmpty())
        assertEquals(0, mapper.emptyFrameStreak)

        for (i in 2..4) {
            val skipped = pipeline.frame(i)
            assertSame(frame1.snapshot, skipped.snapshot)
            assertTrue(skipped.fittedFaces.isEmpty())
            assertTrue(skipped.viewOverlays.isEmpty())
            assertEquals(0, mapper.emptyFrameStreak)
        }
        assertFalse(pipeline.detectorUnavailable)
        // The detector counts its own skipped frames; the pipeline adds no second count.
        assertEquals(0, detector.failedFrames)

        val frame5 = pipeline.frame(5)
        assertEquals(0, mapper.emptyFrameStreak)
        assertEquals(frame1.snapshot.trackedQuads.map { it.face }, frame5.snapshot.trackedQuads.map { it.face })
        assertEquals(0, frame5.snapshot.pendingFragmentFaces)
        assertEquals(listOf("frame 0", "frame 1", "skipped 2", "skipped 3", "skipped 4", "frame 5"), tap.events)

        // A control scan that never saw frames 2-4 ends in an equal snapshot.
        val control = ContinuousScanPipeline(ScriptedDetector(face, emptySet(), StickerCenterDetection.SKIPPED))
        val controlLast = listOf(0, 1, 5).map { control.frame(it) }.last()
        assertEquals(controlLast.snapshot, frame5.snapshot)
    }

    @Test
    fun `three real empty views still reach the mapper and clear tracking`() {
        val mapper = ContinuousFaceletMapper()
        val empty = StickerCenterDetection(emptyList(), WIDTH, HEIGHT)
        assertFalse(empty.skipped)
        val tap = RecordingTap()
        val pipeline =
            ContinuousScanPipeline(ScriptedDetector(face, setOf(2, 3, 4), empty), mapper = mapper).apply { this.tap = tap }

        pipeline.frame(0)
        assertTrue(
            pipeline
                .frame(1)
                .snapshot.trackedQuads
                .isNotEmpty(),
        )
        val last = (2..4).map { pipeline.frame(it) }.last()
        assertEquals(3, mapper.emptyFrameStreak)
        assertTrue(last.snapshot.trackedQuads.isEmpty())
        assertEquals(listOf("frame 0", "frame 1", "frame 2", "frame 3", "frame 4"), tap.events)
    }

    @Test
    fun `a skip inside a run of real empty views neither resets nor bumps the empty-frame streak`() {
        val mapper = ContinuousFaceletMapper()
        val empty = StickerCenterDetection(emptyList(), WIDTH, HEIGHT)
        val misses = mapOf(2 to empty, 3 to empty, 4 to StickerCenterDetection.SKIPPED, 5 to empty)
        val tap = RecordingTap()
        val pipeline =
            ContinuousScanPipeline(ScriptedDetector(face, emptySet(), empty, misses), mapper = mapper).apply { this.tap = tap }

        pipeline.frame(0)
        assertTrue(
            pipeline
                .frame(1)
                .snapshot.trackedQuads
                .isNotEmpty(),
        )
        val streaks =
            (2..5).map { i ->
                val out = pipeline.frame(i)
                if (i == 5) assertTrue(out.snapshot.trackedQuads.isEmpty())
                mapper.emptyFrameStreak
            }
        assertEquals(listOf(1, 2, 2, 3), streaks)
        assertEquals(listOf("frame 0", "frame 1", "frame 2", "frame 3", "skipped 4", "frame 5"), tap.events)
    }

    @Test
    fun `the LiteRT detector's short frame is a skipped frame in the pipeline, counted once`() {
        val session = CountingStickerCenterSession()
        val mapper = ContinuousFaceletMapper()
        val tap = RecordingTap()
        val pipeline = ContinuousScanPipeline(session.detector, mapper = mapper).apply { this.tap = tap }

        // A good frame with no cube: an empty view, which the mapper sees.
        val first = pipeline.processFrame(0, makeBlankYuv(64, 48), rotationDegrees = 0)
        assertEquals(1, mapper.emptyFrameStreak)

        val short = makeBlankYuv(64, 48).apply { y.buffer.limit(1) }
        val skipped = pipeline.processFrame(1, short, rotationDegrees = 0)
        assertSame(first.snapshot, skipped.snapshot)
        assertTrue(skipped.fittedFaces.isEmpty())
        assertTrue(skipped.viewOverlays.isEmpty())
        assertEquals(1, mapper.emptyFrameStreak)
        assertEquals(1, session.detector.failedFrames)
        assertFalse(pipeline.detectorUnavailable)
        assertEquals(1, session.runs)
        assertEquals(listOf("frame 0", "skipped 1"), tap.events)
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 960
        const val FACE_SIZE = 480
        const val FACE_LEFT = 400
        const val FACE_TOP = 240

        class Planes(
            val y: ByteArray,
            val u: ByteArray,
            val v: ByteArray,
        )

        /** A 3x3 grid of distinct colors on a dark background, as I420 planes (pixel stride 1), rendered once. */
        val GOOD: Planes by lazy {
            val colors = List(9) { idx -> Triple(200 - idx * 12, 80 + idx * 12, 120) }
            val cell = FACE_SIZE / 3

            fun rgbAt(
                x: Int,
                y: Int,
            ): Triple<Int, Int, Int> {
                val dx = x - FACE_LEFT
                val dy = y - FACE_TOP
                if (dx < 0 || dy < 0 || dx >= FACE_SIZE || dy >= FACE_SIZE) return Triple(20, 20, 20)
                val (c, r) = dx / cell to dy / cell
                val (ix, iy) = dx % cell to dy % cell
                if (r > 2 || c > 2 || ix < 2 || iy < 2 || ix >= cell - 2 || iy >= cell - 2) return Triple(20, 20, 20)
                return colors[r * 3 + c]
            }
            val y = ByteArray(WIDTH * HEIGHT)
            val u = ByteArray((WIDTH / 2) * (HEIGHT / 2))
            val v = ByteArray((WIDTH / 2) * (HEIGHT / 2))
            for (py in 0 until HEIGHT) {
                for (px in 0 until WIDTH) {
                    val (r, g, b) = rgbAt(px, py)
                    y[py * WIDTH + px] = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255).toByte()
                    if (py % 2 == 0 && px % 2 == 0) {
                        val uv = (py / 2) * (WIDTH / 2) + px / 2
                        u[uv] = (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).roundToInt().coerceIn(0, 255).toByte()
                        v[uv] = (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).roundToInt().coerceIn(0, 255).toByte()
                    }
                }
            }
            Planes(y, u, v)
        }

        fun faceFor(
            left: Double,
            top: Double,
            size: Double,
        ): FittedFace {
            val quad =
                Quad2D(
                    listOf(
                        Point2D(left, top),
                        Point2D(left + size, top),
                        Point2D(left + size, top + size),
                        Point2D(left, top + size),
                    ),
                )
            val centers =
                List(9) { idx -> Point2D(left + (idx % 3 + 0.5) * size / 3.0, top + (idx / 3 + 0.5) * size / 3.0) }
            return FittedFace(quad = quad, score = 1.0, inlierIndices = (0..8).toList(), gridCenters = centers, meanError = 0.0)
        }
    }
}
