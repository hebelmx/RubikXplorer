package dev.cubentia.app.camera.continuous

import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.domain.recognition.continuous.FittedFace
import dev.cubentia.domain.recognition.continuous.Point2D
import dev.cubentia.domain.recognition.continuous.ProjectiveGeometry
import dev.cubentia.domain.recognition.continuous.Quad2D
import dev.cubentia.domain.recognition.continuous.QuadObservation
import dev.cubentia.domain.recognition.continuous.RgbColor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure JVM-testable allocation-light `96×96` bilinear projective quad sampler matching
 * `pc_spike/mapper.py` L174–281 (Story 10.31).
 *
 * Intended for single-threaded use on `analyzerExecutor`: reuses warp and per-cell scratch buffers
 * across frames and steps the projective homography incrementally along each scanline.
 */
internal class ContinuousFaceSampler {
    private val warpRgb = ByteArray(SIDE * SIDE * 3)
    private val warpGray = FloatArray(SIDE * SIDE)

    // Max cell pixel count: center cell uses 3 of 4 corners of ~7x7 = 147 px; non-center uses ~16x16 = 256 px.
    private val rScratch = DoubleArray(MAX_CELL_PIXELS)
    private val gScratch = DoubleArray(MAX_CELL_PIXELS)
    private val bScratch = DoubleArray(MAX_CELL_PIXELS)
    private val lumaScratch = DoubleArray(MAX_CELL_PIXELS)
    private val cornerMeds = Array(4) { DoubleArray(3) }
    private val cornerY0 = IntArray(4)
    private val cornerX0 = IntArray(4)
    private val cornerR = DoubleArray(MAX_CORNER_PIXELS)
    private val cornerG = DoubleArray(MAX_CORNER_PIXELS)
    private val cornerB = DoubleArray(MAX_CORNER_PIXELS)

    /**
     * Sample [face] (whose quad is in upright crop pixels) directly from [frame] (at sensor [rotationDegrees])
     * into a neutral [QuadObservation] ready for `ContinuousFaceletMapper.processFrame`.
     */
    fun sample(
        frame: YuvFrame,
        rotationDegrees: Int,
        face: FittedFace,
    ): QuadObservation? {
        if (frame.left < 0 || frame.top < 0 || frame.width <= 0 || frame.height <= 0 || !face.quad.isFinite()) return null
        val lastX = (frame.right - 1).toDouble()
        val lastY = (frame.bottom - 1).toDouble()
        // Pixel-index convention, as in warpFromRgb and StickerCenterModelTransform.fillYuvInput: a reversed
        // axis mirrors about the last crop pixel, so every rotation reads the same pixels as the upright image.
        val rawCorners =
            face.quad.corners.map { c ->
                when (Math.floorMod(rotationDegrees, FULL_TURN)) {
                    QUARTER_TURN -> Point2D(frame.left + c.y, lastY - c.x)
                    HALF_TURN -> Point2D(lastX - c.x, lastY - c.y)
                    THREE_QUARTER_TURN -> Point2D(lastX - c.y, frame.top + c.x)
                    else -> Point2D(frame.left + c.x, frame.top + c.y)
                }
            }
        val hRaw = ProjectiveGeometry.findHomography(DST_CORNERS, rawCorners) ?: return null
        if (!sampledCellsInside(hRaw, frame.left.toDouble(), frame.top.toDouble(), lastX, lastY)) return null
        warpFromYuv(frame, hRaw)
        return summarizeWarp(face.quad, face.score)
    }

    /**
     * Sample [face] from an upright packed `RGB24` byte buffer (`width * height * 3` bytes).
     * Used by JVM unit tests and RGB-still parity verification.
     */
    fun sampleRgb(
        rgb: ByteArray,
        width: Int,
        height: Int,
        face: FittedFace,
    ): QuadObservation? {
        if (width <= 0 || height <= 0 || rgb.size / 3 / width < height || !face.quad.isFinite()) return null
        val h = ProjectiveGeometry.findHomography(DST_CORNERS, face.quad.corners) ?: return null
        if (!sampledCellsInside(h, 0.0, 0.0, (width - 1).toDouble(), (height - 1).toDouble())) return null
        warpFromRgb(rgb, width, height, h)
        return summarizeWarp(face.quad, face.score)
    }

    /**
     * True when every sampled cell ROI of the warp maps inside the given image bounds. A face whose
     * outer cells leave the image would otherwise read clamped border pixels as sticker colour evidence.
     */
    private fun sampledCellsInside(
        h: DoubleArray,
        minX: Double,
        minY: Double,
        maxX: Double,
        maxY: Double,
    ): Boolean {
        var sign = 0.0
        for (corner in 0 until 4) {
            val wx = if (corner == 1 || corner == 2) ROI_MAX else ROI_MIN
            val wy = if (corner >= 2) ROI_MAX else ROI_MIN
            val den = h[6] * wx + h[7] * wy + h[8]
            if (abs(den) <= 1e-9 || den * sign < 0.0) return false
            sign = den
            val x = (h[0] * wx + h[1] * wy + h[2]) / den
            val y = (h[3] * wx + h[4] * wy + h[5]) / den
            if (x < minX - EDGE_TOLERANCE || x > maxX + EDGE_TOLERANCE || y < minY - EDGE_TOLERANCE || y > maxY + EDGE_TOLERANCE) {
                return false
            }
        }
        return true
    }

    private fun warpFromYuv(
        frame: YuvFrame,
        h: DoubleArray,
    ) {
        val cropLeft = frame.left
        val cropTop = frame.top
        val maxRawX = (frame.right - 1).coerceAtLeast(cropLeft)
        val maxRawY = (frame.bottom - 1).coerceAtLeast(cropTop)
        val cropLeftD = cropLeft.toDouble()
        val cropTopD = cropTop.toDouble()
        val maxRawXD = maxRawX.toDouble()
        val maxRawYD = maxRawY.toDouble()
        val yBuf = frame.y.buffer
        val yRowStride = frame.y.rowStride
        val yPixStride = frame.y.pixelStride
        val uBuf = frame.u.buffer
        val uRowStride = frame.u.rowStride
        val uPixStride = frame.u.pixelStride
        val vBuf = frame.v.buffer
        val vRowStride = frame.v.rowStride
        val vPixStride = frame.v.pixelStride

        val h00 = h[0]
        val h01 = h[1]
        val h02 = h[2]
        val h10 = h[3]
        val h11 = h[4]
        val h12 = h[5]
        val h20 = h[6]
        val h21 = h[7]
        val h22 = h[8]

        var rgbIdx = 0
        var grayIdx = 0
        for (y in 0 until SIDE) {
            var xNum = h01 * y + h02
            var yNum = h11 * y + h12
            var wDen = h21 * y + h22
            for (x in 0 until SIDE) {
                val invW = if (abs(wDen) > 1e-9) 1.0 / wDen else 0.0
                var rawX = xNum * invW
                var rawY = yNum * invW
                if (rawX < cropLeftD) {
                    rawX = cropLeftD
                } else if (rawX > maxRawXD) {
                    rawX = maxRawXD
                }
                if (rawY < cropTopD) {
                    rawY = cropTopD
                } else if (rawY > maxRawYD) {
                    rawY = maxRawYD
                }

                val x0 = rawX.toInt()
                val y0 = rawY.toInt()
                val x1 = if (x0 < maxRawX) x0 + 1 else maxRawX
                val y1 = if (y0 < maxRawY) y0 + 1 else maxRawY
                val wx1 = ((rawX - x0) * 256.0 + 0.5).toInt()
                val wy1 = ((rawY - y0) * 256.0 + 0.5).toInt()
                val wx0 = 256 - wx1
                val wy0 = 256 - wy1

                val row0 = y0 * yRowStride
                val row1 = y1 * yRowStride
                val col0 = x0 * yPixStride
                val col1 = x1 * yPixStride
                val y00 = yBuf.get(row0 + col0).toInt() and 0xFF
                val y01 = yBuf.get(row0 + col1).toInt() and 0xFF
                val y10 = yBuf.get(row1 + col0).toInt() and 0xFF
                val y11 = yBuf.get(row1 + col1).toInt() and 0xFF
                val yv = ((y00 * wx0 + y01 * wx1) * wy0 + (y10 * wx0 + y11 * wx1) * wy1 + 32768) shr 16

                var rpx = (rawX + 0.5).toInt()
                var rpy = (rawY + 0.5).toInt()
                if (rpx > maxRawX) rpx = maxRawX
                if (rpy > maxRawY) rpy = maxRawY
                val cx = rpx shr 1
                val cy = rpy shr 1
                val uv = (uBuf.get(cy * uRowStride + cx * uPixStride).toInt() and 0xFF) - 128
                val vv = (vBuf.get(cy * vRowStride + cx * vPixStride).toInt() and 0xFF) - 128

                var r = yv + ((359 * vv) shr 8)
                var g = yv - ((88 * uv + 183 * vv) shr 8)
                var b = yv + ((454 * uv) shr 8)
                if (r < 0) {
                    r = 0
                } else if (r > 255) {
                    r = 255
                }
                if (g < 0) {
                    g = 0
                } else if (g > 255) {
                    g = 255
                }
                if (b < 0) {
                    b = 0
                } else if (b > 255) {
                    b = 255
                }

                warpRgb[rgbIdx] = r.toByte()
                warpRgb[rgbIdx + 1] = g.toByte()
                warpRgb[rgbIdx + 2] = b.toByte()
                warpGray[grayIdx] = yv.toFloat()

                rgbIdx += 3
                grayIdx++
                xNum += h00
                yNum += h10
                wDen += h20
            }
        }
    }

    private fun warpFromRgb(
        rgb: ByteArray,
        width: Int,
        height: Int,
        h: DoubleArray,
    ) {
        val maxX = (width - 1).toDouble()
        val maxY = (height - 1).toDouble()
        val h00 = h[0]
        val h01 = h[1]
        val h02 = h[2]
        val h10 = h[3]
        val h11 = h[4]
        val h12 = h[5]
        val h20 = h[6]
        val h21 = h[7]
        val h22 = h[8]

        var rgbIdx = 0
        var grayIdx = 0
        for (y in 0 until SIDE) {
            var xNum = h01 * y + h02
            var yNum = h11 * y + h12
            var wDen = h21 * y + h22
            for (x in 0 until SIDE) {
                val invW = if (abs(wDen) > 1e-9) 1.0 / wDen else 0.0
                val sx = (xNum * invW).coerceIn(0.0, maxX)
                val sy = (yNum * invW).coerceIn(0.0, maxY)
                val x0 = floor(sx).toInt().coerceIn(0, width - 1)
                val y0 = floor(sy).toInt().coerceIn(0, height - 1)
                val x1 = min(x0 + 1, width - 1)
                val y1 = min(y0 + 1, height - 1)
                val fx = (sx - x0).toFloat()
                val fy = (sy - y0).toFloat()
                val w00 = (1f - fx) * (1f - fy)
                val w10 = fx * (1f - fy)
                val w01 = (1f - fx) * fy
                val w11 = fx * fy

                val i00 = (y0 * width + x0) * 3
                val i10 = (y0 * width + x1) * 3
                val i01 = (y1 * width + x0) * 3
                val i11 = (y1 * width + x1) * 3

                val r =
                    (
                        w00 * (rgb[i00].toInt() and 0xFF) +
                            w10 * (rgb[i10].toInt() and 0xFF) +
                            w01 * (rgb[i01].toInt() and 0xFF) +
                            w11 * (rgb[i11].toInt() and 0xFF) + 0.5f
                    ).toInt().coerceIn(0, 255)
                val g =
                    (
                        w00 * (rgb[i00 + 1].toInt() and 0xFF) +
                            w10 * (rgb[i10 + 1].toInt() and 0xFF) +
                            w01 * (rgb[i01 + 1].toInt() and 0xFF) +
                            w11 * (rgb[i11 + 1].toInt() and 0xFF) + 0.5f
                    ).toInt().coerceIn(0, 255)
                val b =
                    (
                        w00 * (rgb[i00 + 2].toInt() and 0xFF) +
                            w10 * (rgb[i10 + 2].toInt() and 0xFF) +
                            w01 * (rgb[i01 + 2].toInt() and 0xFF) +
                            w11 * (rgb[i11 + 2].toInt() and 0xFF) + 0.5f
                    ).toInt().coerceIn(0, 255)

                warpRgb[rgbIdx] = r.toByte()
                warpRgb[rgbIdx + 1] = g.toByte()
                warpRgb[rgbIdx + 2] = b.toByte()
                warpGray[grayIdx] = 0.299f * r + 0.587f * g + 0.114f * b

                rgbIdx += 3
                grayIdx++
                xNum += h00
                yNum += h10
                wDen += h20
            }
        }
    }

    private fun summarizeWarp(
        quad: Quad2D,
        score: Double,
    ): QuadObservation {
        val lapVar = laplacianVariance(warpGray)
        val qSharp = ((lapVar - 25.0) / 140.0).coerceIn(0.20, 1.0)
        val qAngle = ProjectiveGeometry.quadQuality(quad).first
        val cellSz = SIDE / 3.0

        val cellRgbs = ArrayList<RgbColor>(9)
        val cellQuals = ArrayList<Double>(9)
        var anyGlare = false

        for (idx in 0 until 9) {
            val r = idx / 3
            val c = idx % 3
            val count =
                if (idx == 4) {
                    gatherLogoTolerantCenter(
                        y0 = ((r + 0.16) * cellSz).roundToInt(),
                        y1 = ((r + 0.84) * cellSz).roundToInt(),
                        x0 = ((c + 0.16) * cellSz).roundToInt(),
                        x1 = ((c + 0.84) * cellSz).roundToInt(),
                    )
                } else {
                    gatherRect(
                        y0 = ((r + 0.26) * cellSz).roundToInt(),
                        y1 = ((r + 0.74) * cellSz).roundToInt(),
                        x0 = ((c + 0.26) * cellSz).roundToInt(),
                        x1 = ((c + 0.74) * cellSz).roundToInt(),
                    )
                }

            var clippedMaxCount = 0
            var clippedMinCount = 0
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            for (i in 0 until count) {
                val rv = rScratch[i]
                val gv = gScratch[i]
                val bv = bScratch[i]
                sumR += rv
                sumG += gv
                sumB += bv
                if (max(rv, max(gv, bv)) > 248.0) clippedMaxCount++
                if (min(rv, min(gv, bv)) > 238.0) clippedMinCount++
                lumaScratch[i] = (rv + gv + bv) / 3.0
            }
            val meanR = sumR / count
            val meanG = sumG / count
            val meanB = sumB / count
            var varR = 0.0
            var varG = 0.0
            var varB = 0.0
            for (i in 0 until count) {
                val dr = rScratch[i] - meanR
                val dg = gScratch[i] - meanG
                val db = bScratch[i] - meanB
                varR += dr * dr
                varG += dg * dg
                varB += db * db
            }
            val stdRgb = (sqrt(varR / count) + sqrt(varG / count) + sqrt(varB / count)) / 3.0

            val medR = medianInPlace(rScratch, count)
            val medG = medianInPlace(gScratch, count)
            val medB = medianInPlace(bScratch, count)
            val minMed = min(medR, min(medG, medB))

            lumaScratch.sort(0, count)
            val brightP95 = sortedPercentile(lumaScratch, count, 95.0)
            val brightP25 = sortedPercentile(lumaScratch, count, 25.0)
            val grad = brightP95 - brightP25

            val clippedMax = clippedMaxCount.toDouble() / count
            val clippedMin = clippedMinCount.toDouble() / count

            var glarePen = 0.0
            if (
                clippedMin > 0.15 ||
                (clippedMax > 0.12 && (minMed > 115.0 || grad > 22.0)) ||
                (brightP95 > 244.0 && grad > 20.0)
            ) {
                glarePen = min(0.95, 0.60 + max(clippedMax, clippedMin) * 1.2 + max(0.0, grad - 18.0) / 80.0)
                anyGlare = true
            }

            val occPen = ((stdRgb - 12.0) / 28.0).coerceIn(0.0, 0.85)
            val q = max(0.01, qAngle * qSharp * (1.0 - glarePen) * (1.0 - occPen))
            cellRgbs.add(RgbColor(medR, medG, medB))
            cellQuals.add(q)
        }

        return QuadObservation(
            quad = quad,
            cellRgbs = cellRgbs,
            cellQualities = cellQuals,
            sharpness = qSharp,
            hasGlare = anyGlare,
            score = score,
        )
    }

    private fun gatherRect(
        y0: Int,
        y1: Int,
        x0: Int,
        x1: Int,
    ): Int {
        var n = 0
        for (y in y0 until y1) {
            val rowOff = y * SIDE * 3
            for (x in x0 until x1) {
                val idx = rowOff + x * 3
                rScratch[n] = (warpRgb[idx].toInt() and 0xFF).toDouble()
                gScratch[n] = (warpRgb[idx + 1].toInt() and 0xFF).toDouble()
                bScratch[n] = (warpRgb[idx + 2].toInt() and 0xFF).toDouble()
                n++
            }
        }
        return n
    }

    private fun gatherLogoTolerantCenter(
        y0: Int,
        y1: Int,
        x0: Int,
        x1: Int,
    ): Int {
        val ph = y1 - y0
        val pw = x1 - x0
        val bh = max(3, ph / 3)
        val bw = max(3, pw / 3)
        cornerY0[0] = y0
        cornerY0[1] = y0
        cornerY0[2] = y1 - bh
        cornerY0[3] = y1 - bh
        cornerX0[0] = x0
        cornerX0[1] = x1 - bw
        cornerX0[2] = x0
        cornerX0[3] = x1 - bw

        for (k in 0 until 4) {
            var n = 0
            for (y in cornerY0[k] until cornerY0[k] + bh) {
                val rowOff = y * SIDE * 3
                for (x in cornerX0[k] until cornerX0[k] + bw) {
                    val idx = rowOff + x * 3
                    cornerR[n] = (warpRgb[idx].toInt() and 0xFF).toDouble()
                    cornerG[n] = (warpRgb[idx + 1].toInt() and 0xFF).toDouble()
                    cornerB[n] = (warpRgb[idx + 2].toInt() and 0xFF).toDouble()
                    n++
                }
            }
            cornerMeds[k][0] = medianInPlace(cornerR, n)
            cornerMeds[k][1] = medianInPlace(cornerG, n)
            cornerMeds[k][2] = medianInPlace(cornerB, n)
        }

        // Pick the 3 most mutually consistent corners (rejects a logo or finger on 1 corner).
        var worstIdx = 0
        var worstDist = -1.0
        for (i in 0 until 4) {
            var dSum = 0.0
            for (j in 0 until 4) {
                if (i == j) continue
                val dr = cornerMeds[i][0] - cornerMeds[j][0]
                val dg = cornerMeds[i][1] - cornerMeds[j][1]
                val db = cornerMeds[i][2] - cornerMeds[j][2]
                dSum += sqrt(dr * dr + dg * dg + db * db)
            }
            if (dSum > worstDist) {
                worstDist = dSum
                worstIdx = i
            }
        }

        var n = 0
        for (k in 0 until 4) {
            if (k == worstIdx) continue
            for (y in cornerY0[k] until cornerY0[k] + bh) {
                val rowOff = y * SIDE * 3
                for (x in cornerX0[k] until cornerX0[k] + bw) {
                    val idx = rowOff + x * 3
                    rScratch[n] = (warpRgb[idx].toInt() and 0xFF).toDouble()
                    gScratch[n] = (warpRgb[idx + 1].toInt() and 0xFF).toDouble()
                    bScratch[n] = (warpRgb[idx + 2].toInt() and 0xFF).toDouble()
                    n++
                }
            }
        }
        return n
    }

    private companion object {
        const val SIDE = 96
        const val MAX_CELL_PIXELS = SIDE * SIDE
        const val MAX_CORNER_PIXELS = (SIDE / 3) * (SIDE / 3)

        // Outermost warp pixels any cell ROI reads: the inner-48% ROI of the outer cells spans [0.26, 2.74) cells.
        const val ROI_MIN = 8.0
        const val ROI_MAX = 87.0
        const val EDGE_TOLERANCE = 0.5
        const val QUARTER_TURN = 90
        const val HALF_TURN = 180
        const val THREE_QUARTER_TURN = 270
        const val FULL_TURN = 360

        val DST_CORNERS =
            listOf(
                Point2D(0.0, 0.0),
                Point2D((SIDE - 1).toDouble(), 0.0),
                Point2D((SIDE - 1).toDouble(), (SIDE - 1).toDouble()),
                Point2D(0.0, (SIDE - 1).toDouble()),
            )

        fun laplacianVariance(gray: FloatArray): Double {
            var sum = 0.0
            var sumSq = 0.0
            val total = SIDE * SIDE
            for (y in 0 until SIDE) {
                val yUp = if (y == 0) 1 else y - 1
                val yDn = if (y == SIDE - 1) SIDE - 2 else y + 1
                val row = y * SIDE
                val rowUp = yUp * SIDE
                val rowDn = yDn * SIDE
                for (x in 0 until SIDE) {
                    val xLt = if (x == 0) 1 else x - 1
                    val xRt = if (x == SIDE - 1) SIDE - 2 else x + 1
                    val lap =
                        (
                            gray[rowUp + x] +
                                gray[rowDn + x] +
                                gray[row + xLt] +
                                gray[row + xRt] -
                                4.0f * gray[row + x]
                        ).toDouble()
                    sum += lap
                    sumSq += lap * lap
                }
            }
            val mean = sum / total
            return max(0.0, sumSq / total - mean * mean)
        }

        fun medianInPlace(
            values: DoubleArray,
            count: Int,
        ): Double {
            values.sort(0, count)
            val mid = count / 2
            return if (count % 2 == 1) values[mid] else 0.5 * (values[mid - 1] + values[mid])
        }

        fun sortedPercentile(
            sorted: DoubleArray,
            count: Int,
            p: Double,
        ): Double {
            if (count <= 0) return 0.0
            if (count == 1) return sorted[0]
            val pos = (p / 100.0) * (count - 1)
            val lo = floor(pos).toInt().coerceIn(0, count - 1)
            val hi = ceil(pos).toInt().coerceIn(0, count - 1)
            val frac = pos - lo
            return sorted[lo] * (1.0 - frac) + sorted[hi] * frac
        }
    }
}
