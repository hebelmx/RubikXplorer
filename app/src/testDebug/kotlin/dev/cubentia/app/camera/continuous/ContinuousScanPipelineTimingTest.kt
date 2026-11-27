package dev.cubentia.app.camera.continuous

import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.StickerCenterDetector
import dev.cubentia.app.camera.StickerCenterPeak
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.YuvPlane
import dev.cubentia.domain.recognition.continuous.FittedFace
import dev.cubentia.domain.recognition.continuous.Point2D
import dev.cubentia.domain.recognition.continuous.Quad2D
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * GH #406: the pipeline times each stage with its clock. The fake clock advances 1 ms per read, so a read frame's
 * detector, fit, sample and map stages are 1 ms each and its analyzer 7 ms; inference is the detection's own
 * `inferenceMs`; a frame the detector skipped counts only in frames and analyzer, and a frame the sampler cannot read
 * adds detector and inference samples but no fit, sample or map sample and is not read.
 */
class ContinuousScanPipelineTimingTest {
    private class StepClock : NanoClock {
        var now = 0L
        var reads = 0

        override fun nanoTime(): Long {
            reads++
            now += 1_000_000L
            return now
        }
    }

    private class ScriptedDetector(
        private val face: FittedFace,
        private val skipFrames: Set<Int>,
    ) : StickerCenterDetector {
        override val unavailable = false
        private var calls = 0

        override fun detect(
            frame: YuvFrame,
            rotationDegrees: Int,
            viewWidth: Int,
            viewHeight: Int,
        ): StickerCenterDetection {
            val index = calls++
            if (index in skipFrames) return StickerCenterDetection.SKIPPED
            return StickerCenterDetection(
                peaks = face.gridCenters.map { p -> StickerCenterPeak(p.x, p.y, 0.95f, p.x / WIDTH, p.y / HEIGHT) },
                uprightWidth = WIDTH,
                uprightHeight = HEIGHT,
                inferenceMs = 4.25 + index,
            )
        }
    }

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

    /** The analyzer's order: [ContinuousScanPipeline.analyzerStart], processing, [ContinuousScanPipeline.analyzerEnd]. */
    private fun ContinuousScanPipeline.analyse(
        frameIndex: Int,
        frame: YuvFrame,
        rotationDegrees: Int,
        viewWidth: Int = 0,
        viewHeight: Int = 0,
    ): ContinuousPipelineFrame {
        analyzerStart()
        return processFrame(frameIndex, frame, rotationDegrees, viewWidth, viewHeight).also { analyzerEnd() }
    }

    private fun field(
        line: String,
        name: String,
    ) = Regex("(?:^| )$name=(\\S+)").find(line)!!.groupValues[1]

    @Test
    fun `each stage is timed with the clock and a skipped frame counts only in frames and analyzer`() {
        val clock = StepClock()
        val windows = mutableListOf<String>()
        val face = faceFor(FACE_LEFT.toDouble(), FACE_TOP.toDouble(), FACE_SIZE.toDouble())
        val pipeline =
            ContinuousScanPipeline(
                ScriptedDetector(face, skipFrames = setOf(1)),
                timings = ContinuousStageTimings(clock, { windows += it }),
            )
        assertNull(pipeline.timingRunLine())

        val first = pipeline.analyse(0, goodFrame(), rotationDegrees = 0, viewWidth = 480, viewHeight = 480)
        assertEquals(1, first.fittedFaces.size)
        assertEquals(8, clock.reads)
        pipeline.analyse(1, goodFrame(), rotationDegrees = 0, viewWidth = 480, viewHeight = 480)
        assertEquals(12, clock.reads)
        // reset() starts a new scan; the run line covers the pipeline's whole life.
        pipeline.reset()
        pipeline.analyse(0, goodFrame(), rotationDegrees = 0, viewWidth = 480, viewHeight = 480)

        val line = checkNotNull(pipeline.timingRunLine())
        assertEquals("3", field(line, "frames"))
        assertEquals("2", field(line, "read"))
        assertEquals("1.0/1.0/1.0", field(line, "detectorMs"))
        // inferenceMs from the detection: 4.25 and 6.25 (frame 2 of the detector).
        assertEquals("4.3/6.3/6.3", field(line, "inferenceMs"))
        assertEquals("1.0/1.0/1.0", field(line, "fitMs"))
        assertEquals("1.0/1.0/1.0", field(line, "sampleMs"))
        assertEquals("1.0/1.0/1.0", field(line, "mapMs"))
        // Read frames 7 ms, the skipped frame 3 ms.
        assertEquals("7.0/7.0/7.0", field(line, "analyzerMs"))
        assertEquals("2", field(line, "fitted"))
        assertTrue(field(line, "observations").toInt() >= 1, line)
        assertTrue(windows.isEmpty())
    }

    @Test
    fun `a skipped frame alone has no stage samples`() {
        val clock = StepClock()
        val face = faceFor(FACE_LEFT.toDouble(), FACE_TOP.toDouble(), FACE_SIZE.toDouble())
        val pipeline =
            ContinuousScanPipeline(ScriptedDetector(face, skipFrames = setOf(0)), timings = ContinuousStageTimings(clock, {}))
        pipeline.analyse(0, goodFrame(), rotationDegrees = 0)
        assertEquals(
            "frames=1 read=0 fps=333.3 detectorMs=- inferenceMs=- fitMs=- sampleMs=- mapMs=- analyzerMs=3.0/3.0/3.0 fitted=0 observations=0",
            pipeline.timingRunLine(),
        )
    }

    @Test
    fun `a frame the sampler cannot read records detector and inference only`() {
        val clock = StepClock()
        val face = faceFor(FACE_LEFT.toDouble(), FACE_TOP.toDouble(), FACE_SIZE.toDouble())
        val pipeline = ContinuousScanPipeline(ScriptedDetector(face, emptySet()), timings = ContinuousStageTimings(clock, {}))
        // GH #397: the Y plane ends inside the face, so the sampler throws and the pipeline skips the frame.
        val short = goodFrame().apply { y.buffer.limit((FACE_TOP + FACE_SIZE / 2) * WIDTH) }
        val skipped = pipeline.analyse(0, short, rotationDegrees = 0)
        assertTrue(skipped.fittedFaces.isEmpty())
        val line = checkNotNull(pipeline.timingRunLine())
        assertEquals("1", field(line, "frames"))
        assertEquals("0", field(line, "read"))
        assertEquals("1.0/1.0/1.0", field(line, "detectorMs"))
        assertEquals("4.3/4.3/4.3", field(line, "inferenceMs"))
        assertEquals("-", field(line, "fitMs"))
        assertEquals("-", field(line, "sampleMs"))
        assertEquals("-", field(line, "mapMs"))
        assertEquals("0", field(line, "fitted"))
        assertEquals("0", field(line, "observations"))
    }

    @Test
    fun `without timings the pipeline has no run line`() {
        val face = faceFor(FACE_LEFT.toDouble(), FACE_TOP.toDouble(), FACE_SIZE.toDouble())
        val pipeline = ContinuousScanPipeline(ScriptedDetector(face, emptySet()), timings = null)
        pipeline.analyse(0, goodFrame(), rotationDegrees = 0)
        assertNull(pipeline.timingRunLine())
    }

    @Test
    fun `a frame is recorded only when the analyzer interval closes`() {
        val clock = StepClock()
        val face = faceFor(FACE_LEFT.toDouble(), FACE_TOP.toDouble(), FACE_SIZE.toDouble())
        val pipeline = ContinuousScanPipeline(ScriptedDetector(face, emptySet()), timings = ContinuousStageTimings(clock, {}))
        pipeline.analyzerStart()
        pipeline.processFrame(0, goodFrame(), rotationDegrees = 0)
        assertNull(pipeline.timingRunLine())
        // Work after processing (frameDone, delivery) is part of the analyzer stage: 2 more ms.
        clock.now += 2_000_000L
        pipeline.analyzerEnd()
        assertEquals("9.0/9.0/9.0", field(checkNotNull(pipeline.timingRunLine()), "analyzerMs"))
        // A second end without a start records nothing.
        pipeline.analyzerEnd()
        assertEquals("1", field(checkNotNull(pipeline.timingRunLine()), "frames"))
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
