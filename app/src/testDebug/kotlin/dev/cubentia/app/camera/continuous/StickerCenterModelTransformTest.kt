package dev.cubentia.app.camera.continuous

import dev.cubentia.app.camera.ViewfinderGrid
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.YuvPlane
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.math.abs

class StickerCenterModelTransformTest {
    @Test
    fun `letterbox computes zero-padded geometry for landscape, portrait, square, and degenerate sizes`() {
        val landscape = checkNotNull(StickerCenterModelTransform.letterbox(640, 480))
        assertEquals(256, landscape.resizedWidth)
        assertEquals(192, landscape.resizedHeight)
        assertEquals(0, landscape.padLeft)
        assertEquals(32, landscape.padTop)

        val portrait = checkNotNull(StickerCenterModelTransform.letterbox(480, 640))
        assertEquals(192, portrait.resizedWidth)
        assertEquals(256, portrait.resizedHeight)
        assertEquals(32, portrait.padLeft)
        assertEquals(0, portrait.padTop)

        val square = checkNotNull(StickerCenterModelTransform.letterbox(206, 206))
        assertEquals(256, square.resizedWidth)
        assertEquals(256, square.resizedHeight)
        assertEquals(0, square.padLeft)
        assertEquals(0, square.padTop)

        assertNull(StickerCenterModelTransform.letterbox(0, 480))
        assertNull(StickerCenterModelTransform.letterbox(640, -1))
    }

    @Test
    fun `fillYuvInput honors all four sensor rotations, non-zero cropRect, and zero-pads letterbox bars`() {
        // Raw sensor buffer 80x60 with active cropRect [8..72) x [6..54) -> crop 64x48.
        val rawW = 80
        val rawH = 60
        val cropL = 8
        val cropT = 6
        val cropR = 72
        val cropB = 54
        // Place a bright white block in the top-left of the raw crop, dark elsewhere.
        val yBytes = ByteArray(rawW * rawH) { 16 }
        val uBytes = ByteArray((rawW / 2) * (rawH / 2)) { 128.toByte() }
        val vBytes = ByteArray((rawW / 2) * (rawH / 2)) { 128.toByte() }
        for (y in cropT until cropT + 16) {
            for (x in cropL until cropL + 16) {
                yBytes[y * rawW + x] = 240.toByte()
            }
        }
        val frame =
            YuvFrame(
                y = YuvPlane(ByteBuffer.wrap(yBytes), rawW, 1),
                u = YuvPlane(ByteBuffer.wrap(uBytes), rawW / 2, 1),
                v = YuvPlane(ByteBuffer.wrap(vBytes), rawW / 2, 1),
                left = cropL,
                top = cropT,
                right = cropR,
                bottom = cropB,
            )

        // Where the raw crop's top-left corner moves in upright letterboxed space for each clockwise rotation:
        val expectedQuadrant =
            mapOf(
                0 to (0.12 to 0.15), // top-left
                90 to (0.85 to 0.12), // top-right
                180 to (0.88 to 0.85), // bottom-right
                270 to (0.15 to 0.88), // bottom-left
            )

        val out = ByteArray(StickerCenterModelContract.INPUT_BYTES) { 77 }
        for ((rot, frac) in expectedQuadrant) {
            val params = assertNotNullAndReturn(StickerCenterModelTransform.fillYuvInput(frame, rot, out))
            // Letterbox border must be strictly zero-filled.
            if (params.padTop > 0) {
                assertEquals(0, out[0].toInt() and 0xFF, "rot=$rot top border not zero")
            }
            if (params.padLeft > 0) {
                assertEquals(0, out[(128 * 256 + 0) * 3].toInt() and 0xFF, "rot=$rot left border not zero")
            }
            val sampleX = params.padLeft + (params.resizedWidth * frac.first).toInt()
            val sampleY = params.padTop + (params.resizedHeight * frac.second).toInt()
            val luma = out[(sampleY * 256 + sampleX) * 3].toInt() and 0xFF
            assertTrue(luma > 200, "rot=$rot expected bright marker at ($sampleX,$sampleY), got $luma")

            val oppX = params.padLeft + (params.resizedWidth * (1.0 - frac.first)).toInt()
            val oppY = params.padTop + (params.resizedHeight * (1.0 - frac.second)).toInt()
            val oppLuma = out[(oppY * 256 + oppX) * 3].toInt() and 0xFF
            assertTrue(oppLuma < 40, "rot=$rot expected dark opposite quadrant at ($oppX,$oppY), got $oppLuma")
        }
    }

    @Test
    fun `fillRgbArrayInput handles both upsampling and downsampling with zeroed borders`() {
        val out = ByteArray(StickerCenterModelContract.INPUT_BYTES) { 99 }
        val small = ByteArray(206 * 206 * 3) { 120 }
        val upParams = assertNotNullAndReturn(StickerCenterModelTransform.fillRgbArrayInput(small, 206, 206, out))
        assertEquals(256, upParams.resizedWidth)
        assertEquals(120, out[(128 * 256 + 128) * 3].toInt() and 0xFF)

        val rect = ByteArray(422 * 300 * 3) { 180.toByte() }
        val downParams = assertNotNullAndReturn(StickerCenterModelTransform.fillRgbArrayInput(rect, 422, 300, out))
        assertTrue(downParams.padTop > 0)
        assertEquals(0, out[0].toInt() and 0xFF)
        assertEquals(180, out[(128 * 256 + 128) * 3].toInt() and 0xFF)
    }

    @Test
    fun `decodePeaks applies 3x3 NMS, threshold, maxPeaks cap, non-finite guard, and ViewfinderGrid inverse`() {
        val params = checkNotNull(StickerCenterModelTransform.letterbox(480, 640))
        val output = FloatArray(StickerCenterModelContract.OUTPUT_FLOATS)

        // Plant a local max at (gx=32, gy=20) with score=0.92 and sub-cell offset (+0.25, -0.25),
        // plus a slightly weaker 8-neighbor that must be suppressed by 3x3 NMS.
        fun setCell(
            gx: Int,
            gy: Int,
            prob: Float,
            dx: Float,
            dy: Float,
        ) {
            val base = (gy * 64 + gx) * 3
            output[base] = prob
            output[base + 1] = dx
            output[base + 2] = dy
        }
        setCell(32, 20, 0.92f, 0.25f, -0.25f)
        setCell(33, 20, 0.80f, 0f, 0f) // suppressed neighbor
        setCell(10, 10, 0.44f, 0f, 0f) // below 0.45 threshold
        setCell(20, 20, Float.NaN, 0f, 0f) // non-finite prob ignored
        setCell(25, 25, 0.85f, Float.POSITIVE_INFINITY, 0f) // non-finite offset ignored

        val peaks =
            StickerCenterModelTransform.decodePeaks(
                output = output,
                params = params,
                cropLeft = 0,
                cropTop = 0,
                cropRight = 640,
                cropBottom = 480,
                rotationDegrees = 90,
                viewWidth = 1080,
                viewHeight = 1920,
            )
        assertEquals(1, peaks.size)
        val peak = peaks.single()
        val expectedPxIn = (32 + 0.5 + 0.25) * 4.0
        val expectedPyIn = (20 + 0.5 - 0.25) * 4.0
        val expectedUx = (expectedPxIn - params.padLeft) / params.scale
        val expectedUy = (expectedPyIn - params.padTop) / params.scale
        assertEquals(expectedUx, peak.x, 1e-4)
        assertEquals(expectedUy, peak.y, 1e-4)
        assertEquals(0.92f, peak.score, 1e-6f)

        // Round-trip check against ViewfinderGrid.frameToViewFraction directly.
        val (fx, fy) = StickerCenterModelTransform.uprightToFrame(peak.x, peak.y, 0, 0, 640, 480, 90)
        val (vx, vy) = ViewfinderGrid.frameToViewFraction(fx, fy, 1080.0, 1920.0, 0, 0, 640, 480, 90)
        assertEquals(vx, peak.viewX, 1e-9)
        assertEquals(vy, peak.viewY, 1e-9)
        assertTrue(peak.viewX in 0.0..1.0 && peak.viewY in 0.0..1.0)

        // Verify maxPeaks cap when > 36 isolated peaks exist.
        output.fill(0f)
        var count = 0
        for (gy in 2 until 62 step 3) {
            for (gx in 10 until 54 step 3) {
                setCell(gx, gy, 0.50f + (count % 40) * 0.01f, 0f, 0f)
                count++
            }
        }
        assertTrue(count > 50)
        val capped =
            StickerCenterModelTransform.decodePeaks(
                output = output,
                params = params,
                cropLeft = 0,
                cropTop = 0,
                cropRight = 640,
                cropBottom = 480,
                rotationDegrees = 90,
            )
        assertEquals(StickerCenterModelContract.MAX_PEAKS, capped.size)
        // Sorted descending by score.
        for (i in 1 until capped.size) {
            assertTrue(capped[i - 1].score >= capped[i].score)
        }
    }

    @Test
    fun `uprightToViewFraction maps upright center to 0_5 0_5 across all rotations and aspect ratios`() {
        for (rot in listOf(0, 90, 180, 270)) {
            val (uw, uh) = StickerCenterModelTransform.uprightDimensions(640, 480, rot)
            val (vx, vy) =
                StickerCenterModelTransform.uprightToViewFraction(
                    ux = uw / 2.0,
                    uy = uh / 2.0,
                    viewWidth = 1080,
                    viewHeight = 1920,
                    cropLeft = 20,
                    cropTop = 10,
                    cropRight = 660,
                    cropBottom = 490,
                    rotationDegrees = rot,
                )
            assertTrue(abs(vx - 0.5) < 1e-9, "rot=$rot vx=$vx")
            assertTrue(abs(vy - 0.5) < 1e-9, "rot=$rot vy=$vy")
        }
    }

    @Test
    fun `uprightToFrame and uprightToViewFraction map off-center quadrants accurately across all rotations with non-zero cropRect`() {
        val cropL = 20
        val cropT = 10
        val cropR = 660 // cropW = 640
        val cropB = 490 // cropH = 480
        val viewW = 1080
        val viewH = 1920
        val testPoints =
            listOf(
                0.15 to 0.25, // top-left quadrant
                0.80 to 0.20, // top-right quadrant
                0.75 to 0.85, // bottom-right quadrant
                0.10 to 0.90, // bottom-left quadrant
                0.0 to 0.0, // exact top-left corner
                1.0 to 1.0, // exact bottom-right corner
            )

        for (rot in listOf(0, 90, 180, 270)) {
            val (uw, uh) = StickerCenterModelTransform.uprightDimensions(cropR - cropL, cropB - cropT, rot)
            val scale = maxOf(viewW.toDouble() / uw, viewH.toDouble() / uh)
            for ((fxFrac, fyFrac) in testPoints) {
                val ux = fxFrac * uw
                val uy = fyFrac * uh

                // Compute raw sensor-frame coordinates directly without calling uprightToFrame.
                val expectedFrame =
                    when (rot) {
                        0 -> (cropL + ux) to (cropT + uy)
                        90 -> (cropL + uy) to (cropB - ux)
                        180 -> (cropR - ux) to (cropB - uy)
                        270 -> (cropR - uy) to (cropT + ux)
                        else -> error("unreachable")
                    }
                val (actualFx, actualFy) =
                    StickerCenterModelTransform.uprightToFrame(ux, uy, cropL, cropT, cropR, cropB, rot)
                assertEquals(expectedFrame.first, actualFx, 1e-9, "rot=$rot ux=$ux uy=$uy fx")
                assertEquals(expectedFrame.second, actualFy, 1e-9, "rot=$rot ux=$ux uy=$uy fy")

                // Independent FILL_CENTER view-fraction derivation directly from upright coordinates.
                val expectedVx = (viewW / 2.0 + (ux - uw / 2.0) * scale) / viewW
                val expectedVy = (viewH / 2.0 + (uy - uh / 2.0) * scale) / viewH
                val (actualVx, actualVy) =
                    StickerCenterModelTransform.uprightToViewFraction(
                        ux = ux,
                        uy = uy,
                        viewWidth = viewW,
                        viewHeight = viewH,
                        cropLeft = cropL,
                        cropTop = cropT,
                        cropRight = cropR,
                        cropBottom = cropB,
                        rotationDegrees = rot,
                    )
                assertEquals(expectedVx, actualVx, 1e-9, "rot=$rot ux=$ux uy=$uy vx")
                assertEquals(expectedVy, actualVy, 1e-9, "rot=$rot ux=$ux uy=$uy vy")

                // Must also match ViewfinderGrid.frameToViewFraction given the independent frame coordinates.
                val (gridVx, gridVy) =
                    ViewfinderGrid.frameToViewFraction(
                        expectedFrame.first,
                        expectedFrame.second,
                        viewW.toDouble(),
                        viewH.toDouble(),
                        cropL,
                        cropT,
                        cropR,
                        cropB,
                        rot,
                    )
                assertEquals(gridVx, actualVx, 1e-9, "rot=$rot gridVx")
                assertEquals(gridVy, actualVy, 1e-9, "rot=$rot gridVy")
            }
        }
    }

    @Test
    fun `fillYuvInput handles semi-planar YUV_420_888 with padded rowStride and chromatic RGB across all rotations`() {
        val rawW = 80
        val rawH = 60
        val cropL = 8
        val cropT = 6
        val cropR = 72 // 64
        val cropB = 54 // 48
        val yRowStride = rawW + 16
        val uvRowStride = rawW + 20 // pixelStride=2 interleaved UV + padding

        // Distinct chromatic colors in the 4 quadrants of the raw crop:
        // TL=Red, TR=Green, BL=Blue, BR=Yellow
        val red = Triple(220, 30, 30)
        val green = Triple(30, 200, 40)
        val blue = Triple(30, 60, 225)
        val yellow = Triple(230, 215, 30)

        val yArr = ByteArray(yRowStride * rawH)
        val uvInterleaved = ByteArray(uvRowStride * (rawH / 2)) { 0x55 }
        val midX = (cropL + cropR) / 2
        val midY = (cropT + cropB) / 2
        for (ry in 0 until rawH) {
            for (rx in 0 until rawW) {
                val (r, g, b) =
                    when {
                        rx < midX && ry < midY -> red
                        rx >= midX && ry < midY -> green
                        rx < midX && ry >= midY -> blue
                        else -> yellow
                    }
                val yVal = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                yArr[ry * yRowStride + rx] = yVal.toByte()
                if (ry % 2 == 0 && rx % 2 == 0) {
                    val uVal = (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).toInt().coerceIn(0, 255)
                    val vVal = (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).toInt().coerceIn(0, 255)
                    val uvOff = (ry / 2) * uvRowStride + (rx / 2) * 2
                    uvInterleaved[uvOff] = uVal.toByte()
                    uvInterleaved[uvOff + 1] = vVal.toByte()
                }
            }
        }
        val uBuf = ByteBuffer.wrap(uvInterleaved)
        val vBuf = ByteBuffer.wrap(uvInterleaved, 1, uvInterleaved.size - 1).slice()
        val frame =
            YuvFrame(
                y = YuvPlane(ByteBuffer.wrap(yArr), yRowStride, 1),
                u = YuvPlane(uBuf, uvRowStride, 2),
                v = YuvPlane(vBuf, uvRowStride, 2),
                left = cropL,
                top = cropT,
                right = cropR,
                bottom = cropB,
            )

        // Expected quadrant colors (TL, TR, BL, BR) in upright view for each clockwise rotation:
        val expectedByRot =
            mapOf(
                0 to listOf(red, green, blue, yellow),
                90 to listOf(blue, red, yellow, green),
                180 to listOf(yellow, blue, green, red),
                270 to listOf(green, yellow, red, blue),
            )
        val quadFracs = listOf(0.25 to 0.25, 0.75 to 0.25, 0.25 to 0.75, 0.75 to 0.75)
        val out = ByteArray(StickerCenterModelContract.INPUT_BYTES)
        val scratch = StickerCenterModelTransform.YuvInputScratch()

        for ((rot, expectedColors) in expectedByRot) {
            val params = assertNotNullAndReturn(StickerCenterModelTransform.fillYuvInput(frame, rot, out, scratch))
            for (q in 0 until 4) {
                val (fx, fy) = quadFracs[q]
                val sx = params.padLeft + (params.resizedWidth * fx).toInt()
                val sy = params.padTop + (params.resizedHeight * fy).toInt()
                val off = (sy * StickerCenterModelContract.INPUT_SIZE + sx) * 3
                val actualR = out[off].toInt() and 0xFF
                val actualG = out[off + 1].toInt() and 0xFF
                val actualB = out[off + 2].toInt() and 0xFF
                val (er, eg, eb) = expectedColors[q]
                assertEquals(er.toDouble(), actualR.toDouble(), 14.0, "rot=$rot q=$q R")
                assertEquals(eg.toDouble(), actualG.toDouble(), 14.0, "rot=$rot q=$q G")
                assertEquals(eb.toDouble(), actualB.toDouble(), 14.0, "rot=$rot q=$q B")
            }
        }
    }

    @Test
    fun `decodePeaks filters padding peaks before maxPeaks cap and guards NaN threshold, bad scale, negative crop, and Int overflow`() {
        // Landscape 640x480 -> padTop=32 (rows 0..7 in 64x64 output grid are padding; rows 56..63 are padding).
        val params = checkNotNull(StickerCenterModelTransform.letterbox(640, 480))
        val output = FloatArray(StickerCenterModelContract.OUTPUT_FLOATS)

        fun setCell(
            gx: Int,
            gy: Int,
            prob: Float,
        ) {
            output[(gy * 64 + gx) * 3] = prob
        }

        // Plant > 36 ultra-high-scoring peaks inside the letterbox padding bars (gy = 2, 5, 58, 61).
        var padPeaks = 0
        for (gy in listOf(2, 5, 58, 61)) {
            for (gx in 2 until 62 step 4) {
                setCell(gx, gy, 0.99f)
                padPeaks++
            }
        }
        assertTrue(padPeaks >= 40)

        // Plant exactly 36 lower-scoring valid peaks inside the active image region (gy = 14..50).
        var validPeaks = 0
        for (gy in listOf(14, 26, 38, 50)) {
            for (gx in 6 until 60 step 6) {
                setCell(gx, gy, 0.60f + validPeaks * 0.005f)
                validPeaks++
            }
        }
        assertEquals(StickerCenterModelContract.MAX_PEAKS, validPeaks)

        val decoded =
            StickerCenterModelTransform.decodePeaks(
                output = output,
                params = params,
                cropLeft = 0,
                cropTop = 0,
                cropRight = 640,
                cropBottom = 480,
                rotationDegrees = 0,
            )
        assertEquals(StickerCenterModelContract.MAX_PEAKS, decoded.size)
        assertTrue(decoded.all { it.x in 0.0..640.0 && it.y in 0.0..480.0 })

        // NaN threshold and non-finite / non-positive scale must return empty cleanly.
        assertTrue(
            StickerCenterModelTransform
                .decodePeaks(output, params, 0, 0, 640, 480, 0, threshold = Float.NaN)
                .isEmpty(),
        )
        assertTrue(
            StickerCenterModelTransform
                .decodePeaks(output, params.copy(scale = Double.NaN), 0, 0, 640, 480, 0)
                .isEmpty(),
        )
        assertTrue(
            StickerCenterModelTransform
                .decodePeaks(output, params.copy(scale = 0.0), 0, 0, 640, 480, 0)
                .isEmpty(),
        )

        // Zero / inverted crop bounds in uprightToViewFraction return (0.0, 0.0) without NaN/Infinity.
        assertEquals(
            0.0 to 0.0,
            StickerCenterModelTransform.uprightToViewFraction(10.0, 10.0, 1080, 1920, 100, 100, 100, 200, 0),
        )
        assertEquals(
            0.0 to 0.0,
            StickerCenterModelTransform.uprightToViewFraction(10.0, 10.0, 1080, 1920, 100, 200, 200, 100, 90),
        )

        // Negative frame.left / frame.top rejected cleanly by fillYuvInput.
        val negLeft =
            YuvFrame(
                y = YuvPlane(ByteBuffer.allocate(64 * 48), 64, 1),
                u = YuvPlane(ByteBuffer.allocate(32 * 24), 32, 1),
                v = YuvPlane(ByteBuffer.allocate(32 * 24), 32, 1),
                left = -4,
                top = 0,
                right = 60,
                bottom = 48,
            )
        val negTop =
            YuvFrame(
                y = negLeft.y,
                u = negLeft.u,
                v = negLeft.v,
                left = 0,
                top = -4,
                right = 64,
                bottom = 44,
            )
        val out = ByteArray(StickerCenterModelContract.INPUT_BYTES)
        assertNull(StickerCenterModelTransform.fillYuvInput(negLeft, 0, out))
        assertNull(StickerCenterModelTransform.fillYuvInput(negTop, 0, out))

        // 32-bit Int overflow guard (50_000 * 50_000 * 3 overflows signed Int to negative).
        assertNull(StickerCenterModelTransform.fillRgbArrayInput(ByteArray(16), 50_000, 50_000, out))
    }

    private fun <T> assertNotNullAndReturn(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
