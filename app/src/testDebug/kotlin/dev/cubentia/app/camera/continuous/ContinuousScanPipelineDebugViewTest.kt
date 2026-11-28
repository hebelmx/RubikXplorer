package dev.cubentia.app.camera.continuous

import dev.cubentia.app.camera.ContinuousDebugOverlay
import dev.cubentia.app.camera.QuickScanDebugView
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.StickerCenterDetector
import dev.cubentia.app.camera.StickerCenterPeak
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.YuvPlane
import dev.cubentia.domain.recognition.continuous.Point2D
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * GH #409: with its debug view on, the pipeline attaches every detector peak (view fractions and score), each fitted
 * face's nine lattice cells mapped upright → view exactly as the face outlines are, with inlier flags, and a counts line;
 * a frame it does not read gets the `skipped` overlay; with the view off (the default, and release by the const gate) no
 * overlay leaves the pipeline.
 */
class ContinuousScanPipelineDebugViewTest {
    private class StepClock : NanoClock {
        var now = 0L

        override fun nanoTime(): Long {
            now += 1_000_000L
            return now
        }
    }

    /** Peaks at the upright points [points] (with view fractions for [rotation] and the view), or a skipped frame. */
    private class ScriptedDetector(
        private val points: List<Point2D>,
        private val skip: Boolean = false,
        override val unavailable: Boolean = false,
    ) : StickerCenterDetector {
        override fun detect(
            frame: YuvFrame,
            rotationDegrees: Int,
            viewWidth: Int,
            viewHeight: Int,
        ): StickerCenterDetection {
            if (skip) return StickerCenterDetection.SKIPPED
            val upright = StickerCenterModelTransform.uprightDimensions(frame.width, frame.height, rotationDegrees)
            return StickerCenterDetection(
                peaks =
                    points.mapIndexed { i, p ->
                        val (vx, vy) = view(p, frame, rotationDegrees, viewWidth, viewHeight)
                        StickerCenterPeak(p.x, p.y, 0.5f + i * 0.05f, vx, vy)
                    },
                uprightWidth = upright.first,
                uprightHeight = upright.second,
            )
        }
    }

    @Test
    fun `with the view on each peak and each fitted lattice is attached with a counts line`() {
        val cells = grid(FACE_LEFT, FACE_TOP)
        // Cell 8 has no peak; one outlier peak far from the face.
        val points = cells.take(8) + Point2D(60.0, 60.0)
        val pipeline = ContinuousScanPipeline(ScriptedDetector(points), timings = null, debugView = { true })
        val result = pipeline.processFrame(0, goodFrame(), 0, VIEW_W, VIEW_H)
        val debug = checkNotNull(result.debug)
        assertEquals(
            result.detection.peaks.map { Triple(it.viewX, it.viewY, it.score) },
            debug.peaks.map { Triple(it.viewX, it.viewY, it.score) },
        )
        assertEquals(9, debug.peaks.size)
        assertEquals(1, result.fittedFaces.size)
        val lattice = debug.lattices.single()
        assertEquals(9, lattice.centers.size)
        // The fitter picks its own cell order: the one cell with no peak is the projected cell at the missing point.
        assertEquals(8, lattice.inliers.count { it })
        val empty = result.fittedFaces.single().gridCenters[lattice.inliers.indexOf(false)]
        assertEquals(cells[8].x, empty.x, 1.0)
        assertEquals(cells[8].y, empty.y, 1.0)
        val line = debug.counts
        assertTrue(line.startsWith("peaks=9 fitted=1 sampled="), line)
        assertEquals(
            listOf("peaks", "fitted", "sampled", "kept", "tracked"),
            line.split(" ").map { it.substringBefore("=") },
        )
        assertEquals(
            result.snapshot.trackedQuads.size
                .toString(),
            field(line, "tracked"),
        )
        assertTrue(field(line, "kept").toInt() <= field(line, "sampled").toInt(), line)
    }

    @Test
    fun `a timed pipeline adds this frame's stage timings to the counts line`() {
        val pipeline =
            ContinuousScanPipeline(
                ScriptedDetector(grid(FACE_LEFT, FACE_TOP)),
                timings = ContinuousStageTimings(StepClock(), {}),
                debugView = { true },
            )
        val line = checkNotNull(pipeline.processFrame(0, goodFrame(), 0, VIEW_W, VIEW_H).debug).counts
        assertEquals("1.0", field(line, "detectMs"))
        assertEquals("1.0", field(line, "fitMs"))
        assertEquals("1.0", field(line, "sampleMs"))
        assertEquals("1.0", field(line, "mapMs"))
        assertEquals("1", field(line, "fitted"))
    }

    @Test
    fun `with the view off no overlay leaves the pipeline`() {
        val cells = grid(FACE_LEFT, FACE_TOP)
        val off = ContinuousScanPipeline(ScriptedDetector(cells), timings = null, debugView = { false })
        val frame = off.processFrame(0, goodFrame(), 0, VIEW_W, VIEW_H)
        assertEquals(1, frame.fittedFaces.size)
        assertNull(frame.debug)
        assertNull(
            ContinuousScanPipeline(ScriptedDetector(cells, skip = true), timings = null, debugView = {
                false
            }).processFrame(0, goodFrame(), 0).debug,
        )
        // The default is the process toggle, off until the adb receiver turns it on.
        assertFalse(QuickScanDebugView.toggled)
        assertNull(ContinuousScanPipeline(ScriptedDetector(cells), timings = null).processFrame(0, goodFrame(), 0, VIEW_W, VIEW_H).debug)
    }

    @Test
    fun `a frame the pipeline does not read gets the skipped overlay`() {
        val cells = grid(FACE_LEFT, FACE_TOP)
        val skipped = ContinuousScanPipeline(ScriptedDetector(cells, skip = true), timings = null, debugView = { true })
        assertEquals(ContinuousDebugOverlay.SKIPPED, skipped.processFrame(0, goodFrame(), 0).debug)
        val unavailable = ContinuousScanPipeline(ScriptedDetector(cells, unavailable = true), timings = null, debugView = { true })
        assertEquals(ContinuousDebugOverlay.SKIPPED, unavailable.processFrame(0, goodFrame(), 0).debug)
        val badCrop = ContinuousScanPipeline(ScriptedDetector(cells), timings = null, debugView = { true })
        assertEquals(ContinuousDebugOverlay.SKIPPED, badCrop.processFrame(0, goodFrame(left = -1), 0).debug)
        // GH #397: the Y plane ends inside the face, so the sampler throws.
        val short = goodFrame().apply { y.buffer.limit((FACE_TOP.toInt() + FACE_SIZE / 2) * WIDTH) }
        val sampler = ContinuousScanPipeline(ScriptedDetector(cells), timings = null, debugView = { true })
        val result = sampler.processFrame(0, short, 0)
        assertEquals(ContinuousDebugOverlay.SKIPPED, result.debug)
        assertEquals("skipped", result.debug?.counts)
        val skippedDebug = checkNotNull(result.debug)
        assertTrue(skippedDebug.peaks.isEmpty() && skippedDebug.lattices.isEmpty())
    }

    @Test
    fun `at every rotation a lattice cell lands at its upright fraction, as the outline mapping puts it`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val (uw, uh) = StickerCenterModelTransform.uprightDimensions(WIDTH, HEIGHT, rotation)
            // A view of the upright aspect: an upright fraction is the same view fraction.
            val vw = uw / 2
            val vh = uh / 2
            val pipeline = ContinuousScanPipeline(ScriptedDetector(grid(FACE_LEFT, FACE_TOP)), timings = null, debugView = { true })
            val result = pipeline.processFrame(0, goodFrame(), rotation, vw, vh)
            val face = result.fittedFaces.single()
            val lattice = checkNotNull(result.debug).lattices.single()
            face.gridCenters.zip(lattice.centers).forEach { (upright, view) ->
                assertEquals(upright.x / uw, view.x, 1e-9, "rotation $rotation x")
                assertEquals(upright.y / uh, view.y, 1e-9, "rotation $rotation y")
                val outline = view(upright, goodFrame(), rotation, vw, vh)
                assertEquals(outline.first, view.x, 1e-12, "rotation $rotation outline x")
                assertEquals(outline.second, view.y, 1e-12, "rotation $rotation outline y")
            }
            assertEquals(List(9) { true }, lattice.inliers)
        }
    }

    private fun field(
        line: String,
        name: String,
    ) = Regex("(?:^| )$name=(\\S+)").find(line)!!.groupValues[1]

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 960
        const val VIEW_W = 640
        const val VIEW_H = 480
        const val FACE_SIZE = 480
        const val FACE_LEFT = 400.0
        const val FACE_TOP = 240.0

        /** The same upright → view mapping the pipeline's face outlines use. */
        fun view(
            p: Point2D,
            frame: YuvFrame,
            rotation: Int,
            viewWidth: Int,
            viewHeight: Int,
        ) = StickerCenterModelTransform.uprightToViewFraction(
            p.x,
            p.y,
            viewWidth,
            viewHeight,
            frame.left,
            frame.top,
            frame.right,
            frame.bottom,
            rotation,
        )

        fun grid(
            left: Double,
            top: Double,
        ) = List(9) { idx -> Point2D(left + (idx % 3 + 0.5) * FACE_SIZE / 3.0, top + (idx / 3 + 0.5) * FACE_SIZE / 3.0) }

        class Planes(
            val y: ByteArray,
            val u: ByteArray,
            val v: ByteArray,
        )

        /** A 3x3 grid of distinct colors on a dark background, as I420 planes (pixel stride 1), rendered once. */
        val GOOD: Planes by lazy {
            val colors = List(9) { idx -> Triple(200 - idx * 12, 80 + idx * 12, 120) }
            val cell = FACE_SIZE / 3
            val faceLeft = FACE_LEFT.toInt()
            val faceTop = FACE_TOP.toInt()

            fun rgbAt(
                x: Int,
                y: Int,
            ): Triple<Int, Int, Int> {
                val dx = x - faceLeft
                val dy = y - faceTop
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

        fun goodFrame(left: Int = 0): YuvFrame =
            YuvFrame(
                y = YuvPlane(ByteBuffer.wrap(GOOD.y), WIDTH, 1),
                u = YuvPlane(ByteBuffer.wrap(GOOD.u), WIDTH / 2, 1),
                v = YuvPlane(ByteBuffer.wrap(GOOD.v), WIDTH / 2, 1),
                left = left,
                top = 0,
                right = WIDTH,
                bottom = HEIGHT,
            )
    }
}
