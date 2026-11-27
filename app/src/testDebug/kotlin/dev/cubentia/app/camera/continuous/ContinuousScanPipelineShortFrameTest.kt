package dev.cubentia.app.camera.continuous

import dev.cubentia.app.camera.ContinuousFrameTap
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.StickerCenterDetector
import dev.cubentia.app.camera.StickerCenterPeak
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.YuvPlane
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.FittedFace
import dev.cubentia.domain.recognition.continuous.Point2D
import dev.cubentia.domain.recognition.continuous.Quad2D
import dev.cubentia.domain.recognition.continuous.QuadObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * GH #397 I/O matrix: a 1280x960 frame whose Y plane stops one row before the crop bottom, a row the detector's 256-px
 * model input never reads (it passes the #389 guard), is skipped by the pipeline when the sampler reaches them: the last snapshot, the mapper untouched,
 * the tap told [ContinuousFrameTap.onSkippedFrame], the detector told once ([StickerCenterDetector.countFailedFrame]).
 */
class ContinuousScanPipelineShortFrameTest {
    // A 480-px face whose bottom edge is the frame's last row (959): the warp reads it, the model input does not.
    private val face = faceFor(left = FACE_LEFT.toDouble(), top = FACE_TOP.toDouble(), size = FACE_SIZE.toDouble())

    /** A fresh frame over the shared planes, so a [shortFrame] limit never reaches another frame. */
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

    /** The same frame with its Y plane ending [SHORT_ROWS] row(s) early. */
    private fun shortFrame(): YuvFrame = goodFrame().apply { y.buffer.limit((HEIGHT - SHORT_ROWS) * WIDTH) }

    private class CountingDetector(
        private val face: FittedFace,
    ) : StickerCenterDetector {
        override val unavailable = false
        var failedFrames = 0

        override fun detect(
            frame: YuvFrame,
            rotationDegrees: Int,
            viewWidth: Int,
            viewHeight: Int,
        ): StickerCenterDetection =
            StickerCenterDetection(
                peaks = face.gridCenters.map { p -> StickerCenterPeak(p.x, p.y, 0.95f, p.x / WIDTH, p.y / HEIGHT) },
                uprightWidth = WIDTH,
                uprightHeight = HEIGHT,
            )

        override fun countFailedFrame() {
            failedFrames++
        }
    }

    private class RecordingTap : ContinuousFrameTap {
        val events = mutableListOf<String>()
        val observationCounts = mutableListOf<Int>()

        override fun modelInputBuffer(frameIndex: Int): ByteArray? = null

        override fun onFrame(
            frameIndex: Int,
            observations: List<QuadObservation>,
            snapshot: ContinuousScanSnapshot,
            detection: StickerCenterDetection,
            modelInputFilled: Boolean,
        ) {
            events += "frame $frameIndex"
            observationCounts += observations.size
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

    @Test
    fun `the real detector passes the short frame and the sampler then throws on it, so the pipeline path is exercised`() {
        // #389 guard: the model input never reads the missing row, so the LiteRT detector runs it as a good frame.
        val session = CountingStickerCenterSession()
        assertEquals(WIDTH, session.detector.detect(shortFrame(), rotationDegrees = 0).uprightWidth)
        assertEquals(0, session.detector.failedFrames)
        assertEquals(1, session.runs)
        assertFalse(session.detector.unavailable)

        assertNotNull(ContinuousFaceSampler().sample(goodFrame(), rotationDegrees = 0, face = face))
        assertThrows(IndexOutOfBoundsException::class.java) {
            ContinuousFaceSampler().sample(shortFrame(), rotationDegrees = 0, face = face)
        }
    }

    @Test
    fun `a frame the sampler cannot read is skipped and counted once, and the next valid frame is processed`() {
        val detector = CountingDetector(face)
        val tap = RecordingTap()
        val pipeline = ContinuousScanPipeline(detector).apply { this.tap = tap }

        val frame0 = pipeline.processFrame(0, goodFrame(), rotationDegrees = 0, viewWidth = 480, viewHeight = 480)
        val frame1 = pipeline.processFrame(1, goodFrame(), rotationDegrees = 0, viewWidth = 480, viewHeight = 480)
        assertEquals(1, frame0.fittedFaces.size)
        assertTrue(frame1.snapshot.trackedQuads.isNotEmpty())
        assertEquals(0, detector.failedFrames)

        val skipped = pipeline.processFrame(2, shortFrame(), rotationDegrees = 0, viewWidth = 480, viewHeight = 480)
        assertSame(frame1.snapshot, skipped.snapshot)
        assertEquals(StickerCenterDetection.EMPTY, skipped.detection)
        assertTrue(skipped.fittedFaces.isEmpty())
        assertTrue(skipped.viewOverlays.isEmpty())
        assertFalse(pipeline.detectorUnavailable)
        assertEquals(1, detector.failedFrames)

        val frame3 = pipeline.processFrame(3, goodFrame(), rotationDegrees = 0, viewWidth = 480, viewHeight = 480)
        assertNotSame(frame1.snapshot, frame3.snapshot)
        assertEquals(3, frame3.snapshot.frameIndex)
        assertEquals(1, frame3.fittedFaces.size)
        assertEquals(1, frame3.viewOverlays.size)
        assertEquals(1, detector.failedFrames)

        assertEquals(listOf("frame 0", "frame 1", "skipped 2", "frame 3"), tap.events)
        assertEquals(listOf(1, 1, 1), tap.observationCounts)

        // A control scan that never saw frame 2 ends in an equal snapshot: the skipped frame left no trace in the mapper.
        val control = ContinuousScanPipeline(CountingDetector(face))
        val controlLast =
            listOf(0, 1, 3).map { i -> control.processFrame(i, goodFrame(), rotationDegrees = 0, viewWidth = 480, viewHeight = 480) }.last()
        assertEquals(controlLast.snapshot.committedCount, frame3.snapshot.committedCount)
        assertEquals(controlLast.snapshot.net, frame3.snapshot.net)
        assertEquals(controlLast.snapshot.trackedQuads, frame3.snapshot.trackedQuads)
        assertEquals(controlLast.snapshot, frame3.snapshot)
    }

    @Test
    fun `the LiteRT detector adds a sampler failure to its own failed-frame count without throwing on plain JVM`() {
        val session = CountingStickerCenterSession()
        val detector = session.detector
        detector.countFailedFrame()
        assertEquals(1, detector.failedFrames)
        // A #389 bad frame adds to the same count.
        val short = makeBlankYuv(64, 48).apply { y.buffer.limit(1) }
        assertEquals(StickerCenterDetection.EMPTY, detector.detect(short, 0))
        assertEquals(2, detector.failedFrames)
        detector.countFailedFrame()
        assertEquals(3, detector.failedFrames)
        assertFalse(detector.unavailable)
        assertEquals(0, session.runs)
    }

    @Test
    fun `an Error from sampling is not a bad frame - it reaches the caller and is not counted`(
        @TempDir dir: File,
    ) {
        // A memory-mapped Y plane whose file is truncated after mapping: reading it faults, which the JVM throws as
        // InternalError (an Error), with no change to the sampler. Platforms that do not fault this way skip the test.
        val good = goodFrame()
        val yFile = File(dir, "y.bin")
        // Windows refuses to truncate a mapped file (IOException): the setup itself is part of the assumption.
        val mapping =
            runCatching {
                RandomAccessFile(yFile, "rw").use { raf ->
                    raf.write(
                        ByteArray(good.y.buffer.capacity()).also {
                            good.y.buffer
                                .duplicate()
                                .apply { clear() }
                                .get(it)
                        },
                    )
                    val mapped = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                    raf.setLength(0)
                    mapped
                }
            }
        assumeTrue(mapping.isSuccess, "a mapped file could not be truncated here: ${mapping.exceptionOrNull()}")
        val yBuffer = mapping.getOrThrow()
        val faulting = YuvFrame(YuvPlane(yBuffer, WIDTH, 1), good.u, good.v, good.left, good.top, good.right, good.bottom)
        val direct = runCatching { ContinuousFaceSampler().sample(faulting, rotationDegrees = 0, face = face) }.exceptionOrNull()
        assumeTrue(direct is Error, "reading a truncated mapping did not throw an Error here: $direct")

        val detector = CountingDetector(face)
        val tap = RecordingTap()
        val pipeline = ContinuousScanPipeline(detector).apply { this.tap = tap }
        assertThrows(Error::class.java) {
            pipeline.processFrame(0, faulting, rotationDegrees = 0, viewWidth = 480, viewHeight = 480)
        }
        assertEquals(0, detector.failedFrames)
        assertTrue(tap.events.isEmpty())
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 960
        const val SHORT_ROWS = 1
        const val FACE_LEFT = 400
        const val FACE_SIZE = 480
        const val FACE_TOP = HEIGHT - 1 - FACE_SIZE

        /** The good frame's planes, rendered once (I420, pixel stride 1). */
        val GOOD: Planes by lazy {
            val colors = List(9) { idx -> Triple(200 - idx * 12, 80 + idx * 12, 120) }
            val rgb = renderGridRgb(WIDTH, HEIGHT, left = FACE_LEFT, top = FACE_TOP, size = FACE_SIZE, cellColors = colors)
            val frame = rgbToYuv(rgb, WIDTH, HEIGHT)
            Planes(frame.y.buffer.array(), frame.u.buffer.array(), frame.v.buffer.array())
        }

        class Planes(
            val y: ByteArray,
            val u: ByteArray,
            val v: ByteArray,
        )

        fun renderGridRgb(
            width: Int,
            height: Int,
            left: Int,
            top: Int,
            size: Int,
            cellColors: List<Triple<Int, Int, Int>>,
        ): ByteArray {
            val rgb = ByteArray(width * height * 3) { 20 }
            val cell = size / 3
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    val (cr, cg, cb) = cellColors[r * 3 + c]
                    for (y in top + r * cell + 2 until top + (r + 1) * cell - 2) {
                        for (x in left + c * cell + 2 until left + (c + 1) * cell - 2) {
                            val off = (y * width + x) * 3
                            rgb[off] = cr.toByte()
                            rgb[off + 1] = cg.toByte()
                            rgb[off + 2] = cb.toByte()
                        }
                    }
                }
            }
            return rgb
        }

        fun rgbToYuv(
            rgb: ByteArray,
            width: Int,
            height: Int,
        ): YuvFrame {
            val yBuf = ByteBuffer.allocate(width * height)
            val uBuf = ByteBuffer.allocate((width / 2) * (height / 2))
            val vBuf = ByteBuffer.allocate((width / 2) * (height / 2))
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val off = (y * width + x) * 3
                    val r = rgb[off].toInt() and 0xFF
                    val g = rgb[off + 1].toInt() and 0xFF
                    val b = rgb[off + 2].toInt() and 0xFF
                    yBuf.put(y * width + x, (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255).toByte())
                    if (y % 2 == 0 && x % 2 == 0) {
                        val uvIdx = (y / 2) * (width / 2) + (x / 2)
                        uBuf.put(uvIdx, (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).roundToInt().coerceIn(0, 255).toByte())
                        vBuf.put(uvIdx, (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).roundToInt().coerceIn(0, 255).toByte())
                    }
                }
            }
            return YuvFrame(
                y = YuvPlane(yBuf, width, 1),
                u = YuvPlane(uBuf, width / 2, 1),
                v = YuvPlane(vBuf, width / 2, 1),
                left = 0,
                top = 0,
                right = width,
                bottom = height,
            )
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
