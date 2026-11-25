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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

class ContinuousFaceSamplerTest {
    private val sampler = ContinuousFaceSampler()

    private fun renderGridRgb(
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        size: Int,
        cellColors: List<Triple<Int, Int, Int>>,
        centerLogo: Boolean = false,
        glareOnCell: Int? = null,
    ): ByteArray {
        val rgb = ByteArray(width * height * 3) { 20 }
        val cell = size / 3
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                val idx = r * 3 + c
                val (cr, cg, cb) = cellColors[idx]
                val x0 = left + c * cell + 2
                val y0 = top + r * cell + 2
                val x1 = left + (c + 1) * cell - 2
                val y1 = top + (r + 1) * cell - 2
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        var pr = cr
                        var pg = cg
                        var pb = cb
                        if (centerLogo && idx == 4) {
                            val cx = (x0 + x1) / 2
                            val cy = (y0 + y1) / 2
                            if (abs(x - cx) < cell / 5 && abs(y - cy) < cell / 5) {
                                pr = 15
                                pg = 15
                                pb = 15
                            }
                        }
                        if (glareOnCell == idx) {
                            pr = 254
                            pg = 254
                            pb = 254
                        }
                        val off = (y * width + x) * 3
                        rgb[off] = pr.toByte()
                        rgb[off + 1] = pg.toByte()
                        rgb[off + 2] = pb.toByte()
                    }
                }
            }
        }
        return rgb
    }

    private fun rgbToYuv(
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

    private fun faceFor(
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
            List(9) { idx ->
                Point2D(left + (idx % 3 + 0.5) * size / 3.0, top + (idx / 3 + 0.5) * size / 3.0)
            }
        return FittedFace(quad = quad, score = 1.0, inlierIndices = (0..8).toList(), gridCenters = centers, meanError = 0.0)
    }

    @Test
    fun `samples 9 cell colors accurately and resists a dark center logo`() {
        val colors =
            listOf(
                Triple(210, 40, 42), // R
                Triple(40, 175, 75), // G
                Triple(30, 95, 210), // B
                Triple(230, 210, 35), // Y
                Triple(230, 230, 230), // W center (with dark logo!)
                Triple(235, 120, 32), // O
                Triple(30, 95, 210), // B
                Triple(210, 40, 42), // R
                Triple(40, 175, 75), // G
            )
        val rgb = renderGridRgb(240, 240, left = 48, top = 48, size = 144, cellColors = colors, centerLogo = true)
        val face = faceFor(48.0, 48.0, 144.0)

        val rgbObs = checkNotNull(sampler.sampleRgb(rgb, 240, 240, face))
        assertFalse(rgbObs.hasGlare)
        assertTrue(rgbObs.sharpness in 0.2..1.0)
        // Center cell must read white despite dark logo in its middle.
        assertTrue(rgbObs.cellRgbs[4].r > 220 && rgbObs.cellRgbs[4].g > 220 && rgbObs.cellRgbs[4].b > 220, rgbObs.cellRgbs[4].toString())
        // Non-center cells match their painted RGBs closely.
        for (i in 0 until 9) {
            assertEquals(colors[i].first.toDouble(), rgbObs.cellRgbs[i].r, 8.0)
            assertEquals(colors[i].second.toDouble(), rgbObs.cellRgbs[i].g, 8.0)
            assertEquals(colors[i].third.toDouble(), rgbObs.cellRgbs[i].b, 8.0)
        }

        // YUV sampling matches RGB sampling closely.
        val yuv = rgbToYuv(rgb, 240, 240)
        val yuvObs = checkNotNull(sampler.sample(yuv, rotationDegrees = 0, face = face))
        assertFalse(yuvObs.hasGlare)
        for (i in 0 until 9) {
            assertEquals(colors[i].first.toDouble(), yuvObs.cellRgbs[i].r, 12.0)
            assertEquals(colors[i].second.toDouble(), yuvObs.cellRgbs[i].g, 12.0)
            assertEquals(colors[i].third.toDouble(), yuvObs.cellRgbs[i].b, 12.0)
        }
    }

    @Test
    fun `glare highlight triggers glare penalty while saturated bright neon yellow does not`() {
        val neonYellow = List(9) { Triple(252, 240, 30) }
        val cleanRgb = renderGridRgb(240, 240, left = 48, top = 48, size = 144, cellColors = neonYellow)
        val face = faceFor(48.0, 48.0, 144.0)
        val cleanObs = checkNotNull(sampler.sampleRgb(cleanRgb, 240, 240, face))
        assertFalse(cleanObs.hasGlare, "Uniform bright neon yellow should not trigger glare")

        val glareRgb = renderGridRgb(240, 240, left = 48, top = 48, size = 144, cellColors = neonYellow, glareOnCell = 2)
        val glareObs = checkNotNull(sampler.sampleRgb(glareRgb, 240, 240, face))
        assertTrue(glareObs.hasGlare, "Blown-out white specular cell should trigger glare")
        assertTrue(glareObs.cellQualities[2] < cleanObs.cellQualities[2] * 0.5)
    }

    @Test
    fun `degenerate inputs return null and pipeline maps tracked quads to view fractions`() {
        val emptyYuv =
            YuvFrame(
                YuvPlane(ByteBuffer.allocate(1), 1, 1),
                YuvPlane(ByteBuffer.allocate(1), 1, 1),
                YuvPlane(ByteBuffer.allocate(1), 1, 1),
                0,
                0,
                0,
                0,
            )
        assertNull(sampler.sample(emptyYuv, 0, faceFor(0.0, 0.0, 100.0)))
        assertNull(sampler.sampleRgb(ByteArray(0), 0, 0, faceFor(0.0, 0.0, 100.0)))

        val colors = List(9) { idx -> Triple(200 - idx * 12, 80 + idx * 12, 120) }
        val rgb = renderGridRgb(240, 240, left = 48, top = 48, size = 144, cellColors = colors)
        val yuv = rgbToYuv(rgb, 240, 240)
        val face = faceFor(48.0, 48.0, 144.0)
        val fakeDetector =
            object : StickerCenterDetector {
                override fun detect(
                    frame: YuvFrame,
                    rotationDegrees: Int,
                    viewWidth: Int,
                    viewHeight: Int,
                ): StickerCenterDetection =
                    StickerCenterDetection(
                        peaks = face.gridCenters.map { p -> StickerCenterPeak(p.x, p.y, 0.95f, p.x / 240.0, p.y / 240.0) },
                        uprightWidth = 240,
                        uprightHeight = 240,
                    )
            }
        val pipeline = ContinuousScanPipeline(fakeDetector)
        // Two frames of a single face anchor the master fragment and emit a tracked overlay.
        pipeline.processFrame(0, yuv, rotationDegrees = 0, viewWidth = 480, viewHeight = 480)
        val frame1 = pipeline.processFrame(1, yuv, rotationDegrees = 0, viewWidth = 480, viewHeight = 480)
        assertNotNull(frame1.snapshot)
        assertEquals(1, frame1.fittedFaces.size)
        assertEquals(1, frame1.viewOverlays.size)
        frame1.viewOverlays.single().viewCorners.forEach { corner ->
            assertTrue(corner.x in 0.0..1.0 && corner.y in 0.0..1.0, corner.toString())
        }
    }

    @Test
    fun `samples rotated and cropped semi-planar YUV_420_888 frames accurately across all 4 rotations`() {
        val colors =
            listOf(
                Triple(210, 40, 42), // R
                Triple(40, 175, 75), // G
                Triple(30, 95, 210), // B
                Triple(230, 210, 35), // Y
                Triple(230, 230, 230), // W center
                Triple(235, 120, 32), // O
                Triple(30, 95, 210), // B
                Triple(210, 40, 42), // R
                Triple(40, 175, 75), // G
            )
        val uprightW = 240
        val uprightH = 200
        val uprightRgb = renderGridRgb(uprightW, uprightH, left = 48, top = 28, size = 144, cellColors = colors, centerLogo = true)
        val face = faceFor(48.0, 28.0, 144.0)

        val cropL = 16
        val cropT = 12
        for (rot in listOf(0, 90, 180, 270)) {
            val cropW = if (rot % 180 == 90) uprightH else uprightW
            val cropH = if (rot % 180 == 90) uprightW else uprightH
            val rawW = cropL + cropW + 16
            val rawH = cropT + cropH + 12
            val yRowStride = rawW + 24
            val uvRowStride = rawW + 28
            val yArr = ByteArray(yRowStride * rawH)
            val uvInterleaved = ByteArray(uvRowStride * ((rawH + 1) / 2)) { 0x44 }

            for (ry in cropT until cropT + cropH) {
                for (rx in cropL until cropL + cropW) {
                    val (ux, uy) =
                        when (rot) {
                            0 -> (rx - cropL) to (ry - cropT)
                            90 -> (cropT + cropH - 1 - ry) to (rx - cropL)
                            180 -> (cropL + cropW - 1 - rx) to (cropT + cropH - 1 - ry)
                            270 -> (ry - cropT) to (cropL + cropW - 1 - rx)
                            else -> error("unreachable")
                        }
                    val off = (uy * uprightW + ux) * 3
                    val r = uprightRgb[off].toInt() and 0xFF
                    val g = uprightRgb[off + 1].toInt() and 0xFF
                    val b = uprightRgb[off + 2].toInt() and 0xFF
                    yArr[ry * yRowStride + rx] = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255).toByte()
                    if (ry % 2 == 0 && rx % 2 == 0) {
                        val uvOff = (ry / 2) * uvRowStride + (rx / 2) * 2
                        uvInterleaved[uvOff] = (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).roundToInt().coerceIn(0, 255).toByte()
                        uvInterleaved[uvOff + 1] = (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).roundToInt().coerceIn(0, 255).toByte()
                    }
                }
            }
            val frame =
                YuvFrame(
                    y = YuvPlane(ByteBuffer.wrap(yArr), yRowStride, 1),
                    u = YuvPlane(ByteBuffer.wrap(uvInterleaved), uvRowStride, 2),
                    v = YuvPlane(ByteBuffer.wrap(uvInterleaved, 1, uvInterleaved.size - 1).slice(), uvRowStride, 2),
                    left = cropL,
                    top = cropT,
                    right = cropL + cropW,
                    bottom = cropT + cropH,
                )
            val obs = checkNotNull(sampler.sample(frame, rotationDegrees = rot, face = face))
            assertFalse(obs.hasGlare, "rot=$rot unexpected glare")
            for (i in 0 until 9) {
                assertEquals(colors[i].first.toDouble(), obs.cellRgbs[i].r, 14.0, "rot=$rot cell=$i R")
                assertEquals(colors[i].second.toDouble(), obs.cellRgbs[i].g, 14.0, "rot=$rot cell=$i G")
                assertEquals(colors[i].third.toDouble(), obs.cellRgbs[i].b, 14.0, "rot=$rot cell=$i B")
            }
        }
    }

    @Test
    fun `negative crop bounds, Int overflow, and degenerate pipeline frames are guarded without advancing state`() {
        val face = faceFor(10.0, 10.0, 80.0)
        val negLeft =
            YuvFrame(
                YuvPlane(ByteBuffer.allocate(64 * 64), 64, 1),
                YuvPlane(ByteBuffer.allocate(32 * 32), 32, 1),
                YuvPlane(ByteBuffer.allocate(32 * 32), 32, 1),
                left = -2,
                top = 0,
                right = 62,
                bottom = 64,
            )
        val negTop =
            YuvFrame(
                negLeft.y,
                negLeft.u,
                negLeft.v,
                left = 0,
                top = -2,
                right = 64,
                bottom = 62,
            )
        assertNull(sampler.sample(negLeft, 0, face))
        assertNull(sampler.sample(negTop, 0, face))
        assertNull(sampler.sampleRgb(ByteArray(16), 50_000, 50_000, face))

        var detectCalls = 0
        val countingDetector =
            object : StickerCenterDetector {
                override fun detect(
                    frame: YuvFrame,
                    rotationDegrees: Int,
                    viewWidth: Int,
                    viewHeight: Int,
                ): StickerCenterDetection {
                    detectCalls++
                    return StickerCenterDetection.EMPTY
                }
            }
        val pipeline = ContinuousScanPipeline(countingDetector)
        val out = pipeline.processFrame(frameIndex = 5, frame = negLeft, rotationDegrees = 0, viewWidth = 480, viewHeight = 480)
        assertEquals(0, detectCalls)
        assertTrue(out.fittedFaces.isEmpty())
        assertTrue(out.viewOverlays.isEmpty())
        assertEquals(0, out.snapshot.committedCount)
    }
}
