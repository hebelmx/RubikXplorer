package dev.cubentia.app.camera

import androidx.camera.core.ImageProxy
import dev.cubentia.domain.recognition.CellReadResult
import dev.cubentia.domain.recognition.CellReader
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.FrameQuality
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.Lab
import java.nio.ByteBuffer
import kotlin.math.pow

/** One YUV plane: [buffer] read at `y * rowStride + x * pixelStride` (in that plane's own resolution). */
internal class YuvPlane(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int,
) {
    fun at(
        x: Int,
        y: Int,
    ): Int = buffer.get(y * rowStride + x * pixelStride).toInt() and BYTE_MASK
}

/**
 * A YUV_420_888 frame as the analyzer sees it: full-resolution [y], half-resolution [u] and [v], and the visible crop
 * rectangle (`left`, `top` inclusive; `right`, `bottom` exclusive) in pixel coordinates.
 */
internal class YuvFrame(
    val y: YuvPlane,
    val u: YuvPlane,
    val v: YuvPlane,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Reads one analysis frame into [FrameStats] (Story 10.2). The grid is the centered square 80 % of the crop's shorter
 * side (as in the fixture recorder); each cell's central 60 % is sampled on a regular 12×12 lattice, converted
 * YUV → sRGB → CIELAB and read by [CellReader]. Mean luma, glare fraction and sharpness (variance of the 4-neighbor
 * Laplacian of Y) come from the Y plane inside the grid, on a [STATS_STEP]-pixel lattice. Seams: for each of the 12
 * inner cell edges, the mean Y along the edge's central [CELL_ROI_FRACTION] is profiled across a band of ±[SEAM_BAND]
 * cells around the grid line; the edge has a seam when the profile's darkest point is at most [SEAM_RATIO] of, and at
 * least [MIN_SEAM_DEPTH] below, the darker of its two cells' mean Y (the dark gap between stickers or pieces). Frames are
 * read and dropped: nothing here keeps pixels.
 */
internal object FrameReader {
    const val GRID_FRACTION = 0.8
    const val CELL_ROI_FRACTION = 0.6
    const val LATTICE = 12
    const val STATS_STEP = 2

    /** Half-width of the band profiled across each inner grid line, in cells. Provisional (device session B). */
    const val SEAM_BAND = 0.3

    /** A seam's darkest profile point is at most this fraction of the darker cell's mean Y. Provisional (session B). */
    const val SEAM_RATIO = 0.75

    /** A seam is at least this much darker (Y, 0–255) than the darker cell. Provisional (session B). */
    const val MIN_SEAM_DEPTH = 8.0

    fun read(image: ImageProxy): FrameStats {
        val crop = image.cropRect
        val planes = image.planes

        fun plane(index: Int) = YuvPlane(planes[index].buffer, planes[index].rowStride, planes[index].pixelStride)
        return read(YuvFrame(plane(0), plane(1), plane(2), crop.left, crop.top, crop.right, crop.bottom))
    }

    fun read(frame: YuvFrame): FrameStats {
        if (frame.width <= 0 || frame.height <= 0) return FrameStats(List(FrameQuality.CELLS) { null }, 0.0, 0.0, 0.0, 0)
        val side = minOf(frame.width, frame.height) * GRID_FRACTION
        val gridLeft = frame.left + (frame.width - side) / 2.0
        val gridTop = frame.top + (frame.height - side) / 2.0
        val cell = side / 3
        val cells =
            (0 until FrameQuality.CELLS).map { index ->
                val roiLeft = gridLeft + (index % 3) * cell + cell * (1 - CELL_ROI_FRACTION) / 2
                val roiTop = gridTop + (index / 3) * cell + cell * (1 - CELL_ROI_FRACTION) / 2
                val step = cell * CELL_ROI_FRACTION / LATTICE
                val pixels =
                    (0 until LATTICE * LATTICE).map { point ->
                        val x = (roiLeft + (point % LATTICE + 0.5) * step).toInt().coerceIn(frame.left, frame.right - 1)
                        val y = (roiTop + (point / LATTICE + 0.5) * step).toInt().coerceIn(frame.top, frame.bottom - 1)
                        lab(frame, x, y)
                    }
                (CellReader.read(pixels) as? CellReadResult.Read)?.reading
            }
        val seams = seams(frame, gridLeft, gridTop, cell)
        return stats(frame, cells, gridLeft.toInt(), gridTop.toInt(), side.toInt()).copy(seams = seams)
    }

    /** How many of the 12 inner cell edges show a dark seam (see the class KDoc). */
    private fun seams(
        frame: YuvFrame,
        gridLeft: Double,
        gridTop: Double,
        cell: Double,
    ): Int {
        fun luma(
            x: Double,
            y: Double,
        ): Int = frame.y.at(x.toInt().coerceIn(frame.left, frame.right - 1), y.toInt().coerceIn(frame.top, frame.bottom - 1))

        // Each cell's mean Y over its central ROI.
        val cellLuma =
            (0 until FrameQuality.CELLS).map { index ->
                val roiLeft = gridLeft + (index % 3) * cell + cell * (1 - CELL_ROI_FRACTION) / 2
                val roiTop = gridTop + (index / 3) * cell + cell * (1 - CELL_ROI_FRACTION) / 2
                val step = cell * CELL_ROI_FRACTION / LATTICE
                (0 until LATTICE * LATTICE).sumOf { point ->
                    luma(roiLeft + (point % LATTICE + 0.5) * step, roiTop + (point / LATTICE + 0.5) * step)
                } / (LATTICE * LATTICE).toDouble()
            }
        val across = (2 * SEAM_BAND * cell).toInt().coerceAtLeast(1)
        val along = (CELL_ROI_FRACTION * cell / STATS_STEP).toInt().coerceAtLeast(1)

        /** The darkest mean Y across the band, [lineAt] the grid line and [alongFrom] the edge's central part start. */
        fun valley(
            vertical: Boolean,
            lineAt: Double,
            alongFrom: Double,
        ): Double =
            (0..across).minOf { offset ->
                val crossAt = lineAt - SEAM_BAND * cell + offset
                (0 until along).sumOf { point ->
                    val at = alongFrom + point * STATS_STEP
                    if (vertical) luma(crossAt, at) else luma(at, crossAt)
                } / along.toDouble()
            }

        fun seam(
            valley: Double,
            first: Int,
            second: Int,
        ): Boolean {
            val darker = minOf(cellLuma[first], cellLuma[second])
            return valley <= darker * SEAM_RATIO && darker - valley >= MIN_SEAM_DEPTH
        }
        val margin = cell * (1 - CELL_ROI_FRACTION) / 2
        var count = 0
        for (k in 0..2) {
            for (line in 1..2) {
                // Row k between columns line - 1 and line, then column k between rows line - 1 and line.
                val vertical = valley(true, gridLeft + line * cell, gridTop + k * cell + margin)
                if (seam(vertical, k * 3 + line - 1, k * 3 + line)) count++
                val horizontal = valley(false, gridTop + line * cell, gridLeft + k * cell + margin)
                if (seam(horizontal, (line - 1) * 3 + k, line * 3 + k)) count++
            }
        }
        return count
    }

    private fun stats(
        frame: YuvFrame,
        cells: List<CellReading?>,
        gridLeft: Int,
        gridTop: Int,
        side: Int,
    ): FrameStats {
        // Laplacian neighbors stay inside the crop.
        val x0 = maxOf(gridLeft, frame.left + 1)
        val y0 = maxOf(gridTop, frame.top + 1)
        val x1 = minOf(gridLeft + side, frame.right - 1)
        val y1 = minOf(gridTop + side, frame.bottom - 1)
        var count = 0
        var lumaSum = 0.0
        var glare = 0
        var lapSum = 0.0
        var lapSquares = 0.0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val luma = frame.y.at(x, y)
                lumaSum += luma
                if (luma >= FrameQuality.GLARE_LUMA) glare++
                val laplacian =
                    (4 * luma - frame.y.at(x - 1, y) - frame.y.at(x + 1, y) - frame.y.at(x, y - 1) - frame.y.at(x, y + 1)).toDouble()
                lapSum += laplacian
                lapSquares += laplacian * laplacian
                count++
                x += STATS_STEP
            }
            y += STATS_STEP
        }
        if (count == 0) return FrameStats(cells, 0.0, 0.0, 0.0, 0)
        val lapMean = lapSum / count
        return FrameStats(cells, lumaSum / count, glare.toDouble() / count, lapSquares / count - lapMean * lapMean, 0)
    }

    private fun lab(
        frame: YuvFrame,
        x: Int,
        y: Int,
    ): Lab {
        val yy = frame.y.at(x, y).toDouble()
        val uu = frame.u.at(x / 2, y / 2) - CHROMA_OFFSET
        val vv = frame.v.at(x / 2, y / 2) - CHROMA_OFFSET
        val red = (yy + 1.402 * vv).toInt().coerceIn(0, BYTE_MASK)
        val green = (yy - 0.344136 * uu - 0.714136 * vv).toInt().coerceIn(0, BYTE_MASK)
        val blue = (yy + 1.772 * uu).toInt().coerceIn(0, BYTE_MASK)
        return rgbToLab(red, green, blue)
    }

    /** sRGB (0–255) to CIELAB (D65), the fixture recorder's formula. */
    fun rgbToLab(
        red: Int,
        green: Int,
        blue: Int,
    ): Lab {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        val r = linear(red)
        val g = linear(green)
        val b = linear(blue)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val yy = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883

        fun f(value: Double) = if (value > 0.0088564517) value.pow(1.0 / 3.0) else 7.787037 * value + 16.0 / 116.0
        val fx = f(x)
        val fy = f(yy)
        val fz = f(z)
        return Lab((116.0 * fy - 16.0).coerceIn(0.0, 100.0), 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private const val CHROMA_OFFSET = 128.0
}

private const val BYTE_MASK = 0xff
