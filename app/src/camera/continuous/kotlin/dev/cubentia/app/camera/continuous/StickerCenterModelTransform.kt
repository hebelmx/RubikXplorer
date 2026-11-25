package dev.cubentia.app.camera.continuous

import dev.cubentia.app.camera.StickerCenterPeak
import dev.cubentia.app.camera.ViewfinderGrid
import dev.cubentia.app.camera.YuvFrame
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure JVM-testable coordinate, letterbox, and peak-decode transforms between a camera [YuvFrame]
 * (at any sensor rotation 0°/90°/180°/270° and arbitrary crop rect) and `sticker_centers_v2_int8.tflite`
 * (`UINT8[1,256,256,3]` → `FLOAT32[1,64,64,3]`).
 */
internal object StickerCenterModelTransform {
    internal data class LetterboxParams(
        val uprightWidth: Int,
        val uprightHeight: Int,
        val scale: Double,
        val resizedWidth: Int,
        val resizedHeight: Int,
        val padLeft: Int,
        val padTop: Int,
    )

    /** Reusable LUT buffers for single-threaded [fillYuvInput] calls (avoids per-frame `IntArray` allocations). */
    internal class YuvInputScratch {
        val yCol0 = IntArray(StickerCenterModelContract.INPUT_SIZE)
        val yCol1 = IntArray(StickerCenterModelContract.INPUT_SIZE)
        val wx = IntArray(StickerCenterModelContract.INPUT_SIZE)
        val uCol = IntArray(StickerCenterModelContract.INPUT_SIZE)
        val vCol = IntArray(StickerCenterModelContract.INPUT_SIZE)
    }

    private const val QUARTER_TURN = 90
    private const val HALF_TURN = 180
    private const val THREE_QUARTER_TURN = 270
    private const val FULL_TURN = 360
    private const val PEAK_EPS = 1e-6f

    /** Compute upright dimensions after applying clockwise sensor [rotationDegrees] to crop `[width × height]`. */
    fun uprightDimensions(
        cropWidth: Int,
        cropHeight: Int,
        rotationDegrees: Int,
    ): Pair<Int, Int> {
        val quarterTurn = Math.floorMod(rotationDegrees, HALF_TURN) == QUARTER_TURN
        return if (quarterTurn) cropHeight to cropWidth else cropWidth to cropHeight
    }

    /** Compute zero-padded square letterbox parameters for an upright `[uprightWidth × uprightHeight]` image. */
    fun letterbox(
        uprightWidth: Int,
        uprightHeight: Int,
        inputSize: Int = StickerCenterModelContract.INPUT_SIZE,
    ): LetterboxParams? {
        if (uprightWidth <= 0 || uprightHeight <= 0 || inputSize <= 0) return null
        val scale = min(inputSize.toDouble() / uprightWidth, inputSize.toDouble() / uprightHeight)
        val resizedWidth = (uprightWidth * scale).roundToInt().coerceIn(1, inputSize)
        val resizedHeight = (uprightHeight * scale).roundToInt().coerceIn(1, inputSize)
        val padLeft = (inputSize - resizedWidth) / 2
        val padTop = (inputSize - resizedHeight) / 2
        return LetterboxParams(uprightWidth, uprightHeight, scale, resizedWidth, resizedHeight, padLeft, padTop)
    }

    /**
     * Map continuous upright crop coordinates `(ux, uy)` in `[0, uprightW] × [0, uprightH]`
     * back into raw sensor frame pixel coordinates `(x, y)` (inverse of [ViewfinderGrid.frameToViewFraction]'s rotation).
     */
    fun uprightToFrame(
        ux: Double,
        uy: Double,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
        rotationDegrees: Int,
    ): Pair<Double, Double> =
        when (Math.floorMod(rotationDegrees, FULL_TURN)) {
            QUARTER_TURN -> (cropLeft + uy) to (cropBottom - ux)
            HALF_TURN -> (cropRight - ux) to (cropBottom - uy)
            THREE_QUARTER_TURN -> (cropRight - uy) to (cropTop + ux)
            else -> (cropLeft + ux) to (cropTop + uy)
        }

    /**
     * Map continuous upright crop coordinates `(ux, uy)` to normalized `[0..1]` `PreviewView` coordinates
     * via [ViewfinderGrid.frameToViewFraction].
     */
    fun uprightToViewFraction(
        ux: Double,
        uy: Double,
        viewWidth: Int,
        viewHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
        rotationDegrees: Int,
    ): Pair<Double, Double> {
        if (cropRight <= cropLeft || cropBottom <= cropTop) return 0.0 to 0.0
        val (fx, fy) = uprightToFrame(ux, uy, cropLeft, cropTop, cropRight, cropBottom, rotationDegrees)
        return ViewfinderGrid.frameToViewFraction(
            x = fx,
            y = fy,
            viewWidth = viewWidth.toDouble(),
            viewHeight = viewHeight.toDouble(),
            cropLeft = cropLeft,
            cropTop = cropTop,
            cropRight = cropRight,
            cropBottom = cropBottom,
            rotationDegrees = rotationDegrees,
        )
    }

    /**
     * Direct YUV_420_888 → `UINT8[256,256,3]` zero-padded center-aligned fixed-point bilinear letterbox honoring
     * [rotationDegrees] and [frame] crop bounds, with zero `Bitmap` allocation and reusable [scratch] LUT buffers.
     * Writes `256 * 256 * 3` bytes into [output] and returns [LetterboxParams], or `null` on invalid crop bounds.
     */
    fun fillYuvInput(
        frame: YuvFrame,
        rotationDegrees: Int,
        output: ByteArray,
        scratch: YuvInputScratch = YuvInputScratch(),
    ): LetterboxParams? {
        require(output.size >= StickerCenterModelContract.INPUT_BYTES) {
            "Expected output buffer >= ${StickerCenterModelContract.INPUT_BYTES}, got ${output.size}"
        }
        val cropLeft = frame.left
        val cropTop = frame.top
        val cropW = frame.width
        val cropH = frame.height
        if (cropLeft < 0 || cropTop < 0 || cropW <= 0 || cropH <= 0) return null
        val (uprightW, uprightH) = uprightDimensions(cropW, cropH, rotationDegrees)
        val params = letterbox(uprightW, uprightH) ?: return null

        val inputSize = StickerCenterModelContract.INPUT_SIZE
        val padLeft = params.padLeft
        val padTop = params.padTop
        val nw = params.resizedWidth
        val nh = params.resizedHeight
        val padRight = padLeft + nw
        val padBottom = padTop + nh
        val rowBytes = inputSize * 3

        if (padTop > 0) output.fill(0, 0, padTop * rowBytes)
        if (padBottom < inputSize) output.fill(0, padBottom * rowBytes, inputSize * rowBytes)

        val rot = Math.floorMod(rotationDegrees, FULL_TURN)
        val maxRawX = (frame.right - 1).coerceAtLeast(cropLeft)
        val maxRawY = (frame.bottom - 1).coerceAtLeast(cropTop)
        val maxUxD = (uprightW - 1).toDouble()
        val maxUyD = (uprightH - 1).toDouble()
        val stepX = uprightW.toDouble() / nw
        val stepY = uprightH.toDouble() / nh

        val yBuf = frame.y.buffer
        val yRowStride = frame.y.rowStride
        val yPixStride = frame.y.pixelStride
        val uBuf = frame.u.buffer
        val uRowStride = frame.u.rowStride
        val uPixStride = frame.u.pixelStride
        val vBuf = frame.v.buffer
        val vRowStride = frame.v.rowStride
        val vPixStride = frame.v.pixelStride

        val yCol0 = scratch.yCol0
        val yCol1 = scratch.yCol1
        val wxArr = scratch.wx
        val uCol = scratch.uCol
        val vCol = scratch.vCol

        when (rot) {
            QUARTER_TURN -> {
                for (i in 0 until nw) {
                    val srcUx = ((i + 0.5) * stepX - 0.5).coerceIn(0.0, maxUxD)
                    val rawY = (maxRawY - srcUx).coerceIn(cropTop.toDouble(), maxRawY.toDouble())
                    val r0 = floor(rawY).toInt()
                    val r1 = min(r0 + 1, maxRawY)
                    yCol0[i] = r0 * yRowStride
                    yCol1[i] = r1 * yRowStride
                    wxArr[i] = ((rawY - r0) * 256.0 + 0.5).toInt().coerceIn(0, 256)
                    val cy = (rawY + 0.5).toInt().coerceIn(cropTop, maxRawY) shr 1
                    uCol[i] = cy * uRowStride
                    vCol[i] = cy * vRowStride
                }
            }

            HALF_TURN -> {
                for (i in 0 until nw) {
                    val srcUx = ((i + 0.5) * stepX - 0.5).coerceIn(0.0, maxUxD)
                    val rawX = (maxRawX - srcUx).coerceIn(cropLeft.toDouble(), maxRawX.toDouble())
                    val r0 = floor(rawX).toInt()
                    val r1 = min(r0 + 1, maxRawX)
                    yCol0[i] = r0 * yPixStride
                    yCol1[i] = r1 * yPixStride
                    wxArr[i] = ((rawX - r0) * 256.0 + 0.5).toInt().coerceIn(0, 256)
                    val cx = (rawX + 0.5).toInt().coerceIn(cropLeft, maxRawX) shr 1
                    uCol[i] = cx * uPixStride
                    vCol[i] = cx * vPixStride
                }
            }

            THREE_QUARTER_TURN -> {
                for (i in 0 until nw) {
                    val srcUx = ((i + 0.5) * stepX - 0.5).coerceIn(0.0, maxUxD)
                    val rawY = (cropTop + srcUx).coerceIn(cropTop.toDouble(), maxRawY.toDouble())
                    val r0 = floor(rawY).toInt()
                    val r1 = min(r0 + 1, maxRawY)
                    yCol0[i] = r0 * yRowStride
                    yCol1[i] = r1 * yRowStride
                    wxArr[i] = ((rawY - r0) * 256.0 + 0.5).toInt().coerceIn(0, 256)
                    val cy = (rawY + 0.5).toInt().coerceIn(cropTop, maxRawY) shr 1
                    uCol[i] = cy * uRowStride
                    vCol[i] = cy * vRowStride
                }
            }

            else -> {
                for (i in 0 until nw) {
                    val srcUx = ((i + 0.5) * stepX - 0.5).coerceIn(0.0, maxUxD)
                    val rawX = (cropLeft + srcUx).coerceIn(cropLeft.toDouble(), maxRawX.toDouble())
                    val r0 = floor(rawX).toInt()
                    val r1 = min(r0 + 1, maxRawX)
                    yCol0[i] = r0 * yPixStride
                    yCol1[i] = r1 * yPixStride
                    wxArr[i] = ((rawX - r0) * 256.0 + 0.5).toInt().coerceIn(0, 256)
                    val cx = (rawX + 0.5).toInt().coerceIn(cropLeft, maxRawX) shr 1
                    uCol[i] = cx * uPixStride
                    vCol[i] = cx * vPixStride
                }
            }
        }

        val quarterTurn = rot == QUARTER_TURN || rot == THREE_QUARTER_TURN
        for (iY in 0 until nh) {
            val dy = padTop + iY
            val rowOff = dy * rowBytes
            if (padLeft > 0) output.fill(0, rowOff, rowOff + padLeft * 3)
            if (padRight < inputSize) output.fill(0, rowOff + padRight * 3, rowOff + rowBytes)

            val srcUy = ((iY + 0.5) * stepY - 0.5).coerceIn(0.0, maxUyD)
            val yBase0: Int
            val yBase1: Int
            val wy1: Int
            val uBase: Int
            val vBase: Int
            if (quarterTurn) {
                val rawX =
                    if (rot == QUARTER_TURN) {
                        (cropLeft + srcUy).coerceIn(cropLeft.toDouble(), maxRawX.toDouble())
                    } else {
                        (maxRawX - srcUy).coerceIn(cropLeft.toDouble(), maxRawX.toDouble())
                    }
                val r0 = floor(rawX).toInt()
                val r1 = min(r0 + 1, maxRawX)
                yBase0 = r0 * yPixStride
                yBase1 = r1 * yPixStride
                wy1 = ((rawX - r0) * 256.0 + 0.5).toInt().coerceIn(0, 256)
                val cx = (rawX + 0.5).toInt().coerceIn(cropLeft, maxRawX) shr 1
                uBase = cx * uPixStride
                vBase = cx * vPixStride
            } else {
                val rawY =
                    if (rot == HALF_TURN) {
                        (maxRawY - srcUy).coerceIn(cropTop.toDouble(), maxRawY.toDouble())
                    } else {
                        (cropTop + srcUy).coerceIn(cropTop.toDouble(), maxRawY.toDouble())
                    }
                val r0 = floor(rawY).toInt()
                val r1 = min(r0 + 1, maxRawY)
                yBase0 = r0 * yRowStride
                yBase1 = r1 * yRowStride
                wy1 = ((rawY - r0) * 256.0 + 0.5).toInt().coerceIn(0, 256)
                val cy = (rawY + 0.5).toInt().coerceIn(cropTop, maxRawY) shr 1
                uBase = cy * uRowStride
                vBase = cy * vRowStride
            }
            val wy0 = 256 - wy1

            var outIdx = rowOff + padLeft * 3
            for (iX in 0 until nw) {
                val c0 = yCol0[iX]
                val c1 = yCol1[iX]
                val wx1 = wxArr[iX]
                val wx0 = 256 - wx1
                val y00 = yBuf.get(yBase0 + c0).toInt() and 0xFF
                val y01 = yBuf.get(yBase0 + c1).toInt() and 0xFF
                val y10 = yBuf.get(yBase1 + c0).toInt() and 0xFF
                val y11 = yBuf.get(yBase1 + c1).toInt() and 0xFF
                val yTop = y00 * wx0 + y01 * wx1
                val yBot = y10 * wx0 + y11 * wx1
                val yv = (yTop * wy0 + yBot * wy1 + 32768) shr 16

                val uv = (uBuf.get(uBase + uCol[iX]).toInt() and 0xFF) - 128
                val vv = (vBuf.get(vBase + vCol[iX]).toInt() and 0xFF) - 128

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

                output[outIdx] = r.toByte()
                output[outIdx + 1] = g.toByte()
                output[outIdx + 2] = b.toByte()
                outIdx += 3
            }
        }
        return params
    }

    /**
     * Exact 22-bit fixed-point separable triangle-filter RGB letterbox matching Pillow `Image.BILINEAR`
     * bit-for-bit across both upsampling and downsampling (for exact PC-to-Android still parity against
     * `corner-peaks-parity.json`).
     */
    fun fillRgbArrayInput(
        rgb: ByteArray,
        width: Int,
        height: Int,
        output: ByteArray,
    ): LetterboxParams? {
        require(output.size >= StickerCenterModelContract.INPUT_BYTES) {
            "Expected output buffer >= ${StickerCenterModelContract.INPUT_BYTES}, got ${output.size}"
        }
        if (width <= 0 || height <= 0 || rgb.size.toLong() < width.toLong() * height.toLong() * 3L) return null
        val params = letterbox(width, height) ?: return null
        val inputSize = StickerCenterModelContract.INPUT_SIZE
        val nw = params.resizedWidth
        val nh = params.resizedHeight
        val padLeft = params.padLeft
        val padTop = params.padTop

        output.fill(0, 0, StickerCenterModelContract.INPUT_BYTES)

        val precisionBits = 22
        val halfStep = 1L shl (precisionBits - 1)
        val scaleFixed = (1L shl precisionBits).toDouble()

        fun buildCoeffs(
            inSize: Int,
            outSize: Int,
        ): Triple<IntArray, IntArray, Array<LongArray>> {
            val sc = inSize.toDouble() / outSize.toDouble()
            val fs = max(1.0, sc)
            val starts = IntArray(outSize)
            val ends = IntArray(outSize)
            val coeffs = Array(outSize) { LongArray(0) }
            for (d in 0 until outSize) {
                val center = (d + 0.5) * sc
                val s0 = max(0, (center - fs + 0.5).toInt())
                val s1 = min(inSize, (center + fs + 0.5).toInt())
                starts[d] = s0
                ends[d] = s1
                val ws = DoubleArray(s1 - s0)
                var sum = 0.0
                for (s in s0 until s1) {
                    val dist = abs((s + 0.5 - center) / fs)
                    val w = if (dist < 1.0) 1.0 - dist else 0.0
                    ws[s - s0] = w
                    sum += w
                }
                val inv = if (sum > 0.0) 1.0 / sum else 0.0
                coeffs[d] = LongArray(ws.size) { k -> (ws[k] * inv * scaleFixed).roundToInt().toLong() }
            }
            return Triple(starts, ends, coeffs)
        }

        // Separable horizontal pass (width -> nw), quantized to uint8 matching Pillow ImagingResampleHorizontal.
        val (xStart, xEnd, xCoeffs) = buildCoeffs(width, nw)
        val horiz = ByteArray(height * nw * 3)
        for (sy in 0 until height) {
            val srcRow = sy * width * 3
            val dstRow = sy * nw * 3
            for (dx in 0 until nw) {
                val s0 = xStart[dx]
                val s1 = xEnd[dx]
                val q = xCoeffs[dx]
                var rAcc = halfStep
                var gAcc = halfStep
                var bAcc = halfStep
                for (sx in s0 until s1) {
                    val w = q[sx - s0]
                    val idx = srcRow + sx * 3
                    rAcc += (rgb[idx].toInt() and 0xFF) * w
                    gAcc += (rgb[idx + 1].toInt() and 0xFF) * w
                    bAcc += (rgb[idx + 2].toInt() and 0xFF) * w
                }
                val outIdx = dstRow + dx * 3
                horiz[outIdx] = (rAcc shr precisionBits).toInt().coerceIn(0, 255).toByte()
                horiz[outIdx + 1] = (gAcc shr precisionBits).toInt().coerceIn(0, 255).toByte()
                horiz[outIdx + 2] = (bAcc shr precisionBits).toInt().coerceIn(0, 255).toByte()
            }
        }

        // Separable vertical pass (height -> nh), matching Pillow ImagingResampleVertical.
        val (yStart, yEnd, yCoeffs) = buildCoeffs(height, nh)
        for (dy in 0 until nh) {
            val s0 = yStart[dy]
            val s1 = yEnd[dy]
            val q = yCoeffs[dy]
            val outRow = (padTop + dy) * inputSize * 3 + padLeft * 3
            for (dx in 0 until nw) {
                var rAcc = halfStep
                var gAcc = halfStep
                var bAcc = halfStep
                for (sy in s0 until s1) {
                    val w = q[sy - s0]
                    val idx = (sy * nw + dx) * 3
                    rAcc += (horiz[idx].toInt() and 0xFF) * w
                    gAcc += (horiz[idx + 1].toInt() and 0xFF) * w
                    bAcc += (horiz[idx + 2].toInt() and 0xFF) * w
                }
                val oIdx = outRow + dx * 3
                output[oIdx] = (rAcc shr precisionBits).toInt().coerceIn(0, 255).toByte()
                output[oIdx + 1] = (gAcc shr precisionBits).toInt().coerceIn(0, 255).toByte()
                output[oIdx + 2] = (bAcc shr precisionBits).toInt().coerceIn(0, 255).toByte()
            }
        }
        return params
    }

    private data class CandidatePeak(
        val gx: Int,
        val gy: Int,
        val score: Float,
    )

    /**
     * Decode `FLOAT32[64,64,3]` output (`[hm_prob, dx, dy]`) via 3×3 local-maximum suppression,
     * matching `tools/center-model/eval_litert_center.py`.
     */
    fun decodePeaks(
        output: FloatArray,
        params: LetterboxParams,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
        rotationDegrees: Int,
        viewWidth: Int = 0,
        viewHeight: Int = 0,
        threshold: Float = StickerCenterModelContract.DEFAULT_THRESHOLD,
        maxPeaks: Int = StickerCenterModelContract.MAX_PEAKS,
    ): List<StickerCenterPeak> {
        if (
            output.size < StickerCenterModelContract.OUTPUT_FLOATS ||
            maxPeaks <= 0 ||
            !threshold.isFinite() ||
            !params.scale.isFinite() ||
            params.scale <= 0.0
        ) {
            return emptyList()
        }
        val hmSize = StickerCenterModelContract.HEATMAP_SIZE
        val stride = StickerCenterModelContract.HEATMAP_STRIDE.toDouble()
        val candidates = ArrayList<CandidatePeak>(maxPeaks * 2)

        for (gy in 0 until hmSize) {
            val rowBase = gy * hmSize
            for (gx in 0 until hmSize) {
                val score = output[(rowBase + gx) * 3]
                if (!score.isFinite() || score < threshold) continue
                var isMax = true
                val yMin = max(0, gy - 1)
                val yMax = min(hmSize - 1, gy + 1)
                val xMin = max(0, gx - 1)
                val xMax = min(hmSize - 1, gx + 1)
                loop@ for (ny in yMin..yMax) {
                    val nRow = ny * hmSize
                    for (nx in xMin..xMax) {
                        val nScore = output[(nRow + nx) * 3]
                        if (nScore.isFinite() && nScore > score + PEAK_EPS) {
                            isMax = false
                            break@loop
                        }
                    }
                }
                if (isMax) {
                    candidates.add(CandidatePeak(gx, gy, score))
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()
        candidates.sortByDescending { it.score }

        val peaks = ArrayList<StickerCenterPeak>(min(maxPeaks, candidates.size))
        for (i in candidates.indices) {
            val c = candidates[i]
            val base = (c.gy * hmSize + c.gx) * 3
            val dx = output[base + 1].toDouble()
            val dy = output[base + 2].toDouble()
            if (!dx.isFinite() || !dy.isFinite()) continue
            val pxIn = (c.gx + 0.5 + dx) * stride
            val pyIn = (c.gy + 0.5 + dy) * stride
            val ox = (pxIn - params.padLeft) / params.scale
            val oy = (pyIn - params.padTop) / params.scale
            if (ox >= 0.0 && ox < params.uprightWidth && oy >= 0.0 && oy < params.uprightHeight) {
                val (vx, vy) =
                    uprightToViewFraction(
                        ux = ox,
                        uy = oy,
                        viewWidth = viewWidth,
                        viewHeight = viewHeight,
                        cropLeft = cropLeft,
                        cropTop = cropTop,
                        cropRight = cropRight,
                        cropBottom = cropBottom,
                        rotationDegrees = rotationDegrees,
                    )
                peaks.add(StickerCenterPeak(ox, oy, c.score, vx, vy))
                if (peaks.size >= maxPeaks) break
            }
        }
        return peaks
    }
}
