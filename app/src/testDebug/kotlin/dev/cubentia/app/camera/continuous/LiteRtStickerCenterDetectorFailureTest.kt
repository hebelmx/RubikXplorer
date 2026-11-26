package dev.cubentia.app.camera.continuous

import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.YuvPlane
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** Story 10.31 I/O matrix: missing asset, corrupt buffer, LinkageError, tiny crop, and post-close degrade safely to empty. */
class LiteRtStickerCenterDetectorFailureTest {
    private val validFrame = makeBlankYuv(64, 48)

    @Test
    fun `missing model asset returns empty on every frame and attempts load only once`() {
        var loads = 0
        val detector =
            LiteRtStickerCenterDetector {
                loads++
                throw FileNotFoundException(StickerCenterModelContract.MODEL_ASSET)
            }
        repeat(3) {
            val result = detector.detect(validFrame, rotationDegrees = 90, viewWidth = 1080, viewHeight = 1920)
            assertTrue(result.peaks.isEmpty())
        }
        assertEquals(1, loads)
    }

    @Test
    fun `corrupt model buffer or LinkageError returns empty without crashing`() {
        val corrupt = LiteRtStickerCenterDetector { ByteBuffer.allocateDirect(16) }
        assertTrue(corrupt.detect(validFrame, 0).peaks.isEmpty())
        assertTrue(corrupt.detect(validFrame, 0).peaks.isEmpty())

        var linkageLoads = 0
        val unlinked =
            LiteRtStickerCenterDetector {
                linkageLoads++
                throw UnsatisfiedLinkError("libLiteRt.so missing")
            }
        repeat(2) { assertTrue(unlinked.detect(validFrame, 0).peaks.isEmpty()) }
        assertEquals(1, linkageLoads)
    }

    @Test
    fun `an inference-time failure disables the detector after a single attempt`() {
        val failures =
            listOf<() -> Throwable>(
                { IllegalArgumentException("tensor mismatch") },
                { IllegalStateException("invoke failed") },
                { UnsatisfiedLinkError("libLiteRt.so symbol missing") },
            )
        for (failure in failures) {
            var opens = 0
            var runs = 0
            var closes = 0
            val detector =
                LiteRtStickerCenterDetector(
                    loadModel = { ByteBuffer.allocateDirect(16) },
                    openSession = {
                        opens++
                        object : StickerCenterInferenceSession {
                            override fun run(
                                input: ByteBuffer,
                                output: ByteBuffer,
                            ) {
                                runs++
                                throw failure()
                            }

                            override fun close() {
                                closes++
                            }
                        }
                    },
                )
            repeat(3) { assertTrue(detector.detect(validFrame, rotationDegrees = 0).peaks.isEmpty()) }
            assertTrue(detector.detectRgb(ByteArray(48), 4, 4).peaks.isEmpty())
            assertEquals(0, detector.failedFrames, failure().toString())
            assertEquals(1, opens, failure().toString())
            assertEquals(1, runs, failure().toString())
            assertEquals(1, closes, failure().toString())
        }
    }

    @Test
    fun `a healthy session runs once per frame and is closed exactly once`() {
        val session = CountingStickerCenterSession()
        val detector = session.detector
        repeat(3) { assertTrue(detector.detect(validFrame, rotationDegrees = 90).peaks.isEmpty()) }
        assertEquals(3, session.runs)
        detector.close()
        assertTrue(detector.detect(validFrame, rotationDegrees = 90).peaks.isEmpty())
        assertEquals(3, session.runs)
        assertEquals(1, session.closes)
        assertEquals(0, detector.failedFrames)
    }

    @Test
    fun `short Y planes at every rotation are counted even when Android logging is unavailable`() {
        val session = CountingStickerCenterSession()
        val short = makeBlankYuv(64, 48).apply { y.buffer.limit(1) }
        for ((index, rotation) in listOf(0, 90, 180, 270).withIndex()) {
            assertEquals(StickerCenterDetection.EMPTY, session.detector.detect(short, rotation))
            assertEquals(index + 1, session.detector.failedFrames)
        }
        assertEquals(0, session.runs)
        val recovered = session.detector.detect(validFrame, 0)
        assertEquals(validFrame.width, recovered.uprightWidth)
        assertEquals(4, session.detector.failedFrames)
        assertEquals(1, session.runs)
        assertEquals(1, session.opens)
        assertEquals(0, session.closes)
    }

    @Test
    fun `a short U or V plane skips inference`() {
        val session = CountingStickerCenterSession()
        val shortU = makeBlankYuv(64, 48).apply { u.buffer.limit(1) }
        val shortV = makeBlankYuv(64, 48).apply { v.buffer.limit(1) }
        assertEquals(StickerCenterDetection.EMPTY, session.detector.detect(shortU, 0))
        assertEquals(StickerCenterDetection.EMPTY, session.detector.detect(shortV, 0))
        assertEquals(2, session.detector.failedFrames)
        assertEquals(0, session.runs)
        assertEquals(0, session.closes)
    }

    @Test
    fun `a valid frame after a partly converted bad frame gives inference the same input as a fresh detector`() {
        fun patterned() =
            makeBlankYuv(64, 48).apply {
                for (i in 0 until y.buffer.capacity()) y.buffer.put(i, (i % 200 + 50).toByte())
            }
        // The pattern reaches the model input, so bytes left over from it would show.
        val fresh = CountingStickerCenterSession().also { it.detector.detect(validFrame, 0) }
        val full = CountingStickerCenterSession().also { it.detector.detect(patterned(), 0) }
        assertFalse(fresh.lastInput.contentEquals(full.lastInput))

        // Conversion fails half-way down the frame, after the upper rows were already written.
        val session = CountingStickerCenterSession()
        val truncated = patterned().apply { y.buffer.limit(64 * 24) }
        assertEquals(StickerCenterDetection.EMPTY, session.detector.detect(truncated, 0))
        assertEquals(0, session.runs)
        session.detector.detect(validFrame, 0)
        assertEquals(1, session.runs)
        assertArrayEquals(fresh.lastInput, session.lastInput)
    }

    @Test
    fun `an Error is not a bad frame - it reaches the caller and is not counted`() {
        val fatal = Error("not a bad frame")
        val detector =
            LiteRtStickerCenterDetector(
                loadModel = { ByteBuffer.allocateDirect(16) },
                openSession = {
                    object : StickerCenterInferenceSession {
                        override fun run(
                            input: ByteBuffer,
                            output: ByteBuffer,
                        ): Unit = throw fatal

                        override fun close() = Unit
                    }
                },
            )
        assertSame(fatal, assertThrows(Error::class.java) { detector.detect(validFrame, 0) })
        assertEquals(0, detector.failedFrames)
    }

    @Test
    fun `the pipeline advances past a bad frame and processes the next valid frame`() {
        val session = CountingStickerCenterSession()
        val pipeline = ContinuousScanPipeline(session.detector)
        val short = makeBlankYuv(64, 48).apply { y.buffer.limit(1) }
        val bad = pipeline.processFrame(frameIndex = 0, frame = short, rotationDegrees = 0)
        assertEquals(StickerCenterDetection.EMPTY, bad.detection)
        assertTrue(bad.fittedFaces.isEmpty())
        assertEquals(0, bad.snapshot.frameIndex)
        assertEquals(0, session.runs)

        val good = pipeline.processFrame(frameIndex = 1, frame = validFrame, rotationDegrees = 0)
        assertEquals(validFrame.width, good.detection.uprightWidth)
        assertEquals(1, good.snapshot.frameIndex)
        assertEquals(1, session.runs)
    }

    @Test
    fun `empty or degenerate crop dimensions return empty without touching model loader`() {
        var loads = 0
        val detector =
            LiteRtStickerCenterDetector {
                loads++
                ByteBuffer.allocateDirect(16)
            }
        val zeroWidth = makeBlankYuv(64, 48, cropRight = 0, cropBottom = 48)
        val zeroHeight = makeBlankYuv(64, 48, cropRight = 64, cropBottom = 0)
        val negLeft = YuvFrame(validFrame.y, validFrame.u, validFrame.v, left = -4, top = 0, right = 64, bottom = 48)
        val negTop = YuvFrame(validFrame.y, validFrame.u, validFrame.v, left = 0, top = -4, right = 64, bottom = 48)
        assertTrue(detector.detect(zeroWidth, 0).peaks.isEmpty())
        assertTrue(detector.detect(zeroHeight, 90).peaks.isEmpty())
        assertTrue(detector.detect(negLeft, 0).peaks.isEmpty())
        assertTrue(detector.detect(negTop, 90).peaks.isEmpty())
        assertTrue(detector.detectRgb(ByteArray(0), 0, 0).peaks.isEmpty())
        assertTrue(detector.detectRgb(ByteArray(16), 50_000, 50_000).peaks.isEmpty())
        assertTrue(detector.detectRgb(ByteArray(16), Int.MAX_VALUE, Int.MAX_VALUE).peaks.isEmpty())
        assertEquals(0, loads)
    }

    @Test
    fun `closed detector returns empty safely and feeds cleanly through ContinuousScanPipeline`() {
        var loads = 0
        val detector =
            LiteRtStickerCenterDetector {
                loads++
                throw FileNotFoundException("should not be loaded after close")
            }
        detector.close()
        assertTrue(detector.detect(validFrame, 0).peaks.isEmpty())
        assertEquals(0, loads)

        val pipeline = ContinuousScanPipeline(detector)
        val out = pipeline.processFrame(frameIndex = 0, frame = validFrame, rotationDegrees = 90, viewWidth = 1080, viewHeight = 1920)
        assertTrue(out.fittedFaces.isEmpty())
        assertEquals(ContinuousScanCue.BringIntoView, out.snapshot.cue)
        assertEquals(0, out.snapshot.committedCount)
    }
}

internal fun makeBlankYuv(
    width: Int,
    height: Int,
    cropRight: Int = width,
    cropBottom: Int = height,
): YuvFrame {
    val y = ByteBuffer.allocate(maxOf(1, width * height))
    // Chroma is half resolution rounded up, so an odd-sized frame still has a sample for its last row and column.
    val chromaWidth = maxOf(1, (width + 1) / 2)
    val chromaSize = chromaWidth * maxOf(1, (height + 1) / 2)
    val u = ByteBuffer.allocate(chromaSize)
    val v = ByteBuffer.allocate(chromaSize)
    return YuvFrame(
        y = YuvPlane(y, maxOf(1, width), 1),
        u = YuvPlane(u, chromaWidth, 1),
        v = YuvPlane(v, chromaWidth, 1),
        left = 0,
        top = 0,
        right = cropRight,
        bottom = cropBottom,
    )
}

internal class CountingStickerCenterSession : StickerCenterInferenceSession {
    var opens = 0
        private set
    var runs = 0
        private set
    var closes = 0
        private set

    /** A copy of the bytes the latest inference was given. */
    var lastInput: ByteArray? = null
        private set

    val detector =
        LiteRtStickerCenterDetector(
            loadModel = { ByteBuffer.allocateDirect(16) },
            openSession = {
                opens++
                this
            },
        )

    override fun run(
        input: ByteBuffer,
        output: ByteBuffer,
    ) {
        runs++
        lastInput = ByteArray(input.remaining()).also { input.duplicate().get(it) }
    }

    override fun close() {
        closes++
    }
}
