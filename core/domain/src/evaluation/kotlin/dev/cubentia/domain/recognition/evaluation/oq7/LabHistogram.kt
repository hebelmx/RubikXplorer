package dev.cubentia.domain.recognition.evaluation.oq7

import dev.cubentia.domain.recognition.Lab
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/** A cell's ROI in frame pixels: [x], [y] inclusive, [width] × [height]. */
data class CellRoi(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** The recorder's grid on a frame: centered square of 80% of the short edge, 3×3 row-major; each cell's central 60%. */
object FrameGrid {
    const val GRID_SHARE = 0.8
    const val ROI_SHARE = 0.6

    fun cells(
        width: Int,
        height: Int,
    ): List<CellRoi> {
        val side = min(width, height) * GRID_SHARE
        val left = (width - side) / 2.0
        val top = (height - side) / 2.0
        val cell = side / 3
        val inset = (1 - ROI_SHARE) / 2
        return (0..2).flatMap { row ->
            (0..2).map { column ->
                val x0 = floor(left + (column + inset) * cell).toInt()
                val x1 = floor(left + (column + 1 - inset) * cell).toInt()
                val y0 = floor(top + (row + inset) * cell).toInt()
                val y1 = floor(top + (row + 1 - inset) * cell).toInt()
                CellRoi(x0, y0, x1 - x0, y1 - y0)
            }
        }
    }
}

data class HistogramBin(
    val l: Int,
    val a: Int,
    val b: Int,
)

/** 3-D CIELAB histogram (L 10 bins over [0,100], a and b 16 bins over [−128,128]) with bright pixels masked. */
object LabHistogram {
    const val L_BINS = 10
    const val AB_BINS = 16
    const val L_MAX = 100.0
    const val AB_RANGE = 128.0

    /** Pixels at or above this lightness are specular glare and are masked out. */
    const val MASK_L = 98.0

    /** The PO's issue #153 test: a center is plain when its mode holds at least this share of the unmasked ROI. */
    const val PLAIN_SHARE = 0.60

    fun unmasked(lab: Lab): Boolean = lab.l < MASK_L

    fun bin(lab: Lab): HistogramBin? {
        val l = floor(lab.l * L_BINS / L_MAX).toInt()
        val a = floor((lab.a + AB_RANGE) * AB_BINS / (2 * AB_RANGE)).toInt()
        val b = floor((lab.b + AB_RANGE) * AB_BINS / (2 * AB_RANGE)).toInt()
        return if (l in 0 until L_BINS && a in 0 until AB_BINS && b in 0 until AB_BINS) HistogramBin(l, a, b) else null
    }

    /** Peak bin of the unmasked pixels (ties go to the first bin in L, a, b order); the spike uses OpenCV `calcHist` instead. */
    fun peak(pixels: List<Lab>): HistogramBin? {
        val counts = IntArray(L_BINS * AB_BINS * AB_BINS)
        pixels.filter(::unmasked).mapNotNull(::bin).forEach { counts[(it.l * AB_BINS + it.a) * AB_BINS + it.b]++ }
        val best = counts.indices.maxBy { counts[it] }
        if (counts[best] == 0) return null
        return HistogramBin(best / (AB_BINS * AB_BINS), best / AB_BINS % AB_BINS, best % AB_BINS)
    }

    /** Cell mean, the mode around [peak] (peak bin and its 26 neighbors) and the mode's share of the unmasked pixels. */
    fun feature(
        pixels: List<Lab>,
        peak: HistogramBin?,
    ): CellFeature {
        val unmasked = pixels.filter(::unmasked).ifEmpty { pixels }
        val mean = mean(unmasked)
        if (peak == null) return CellFeature(mean, mean, 0.0)
        val inMode =
            unmasked.filter { pixel ->
                val bin = bin(pixel)
                bin != null && abs(bin.l - peak.l) <= 1 && abs(bin.a - peak.a) <= 1 && abs(bin.b - peak.b) <= 1
            }
        if (inMode.isEmpty()) return CellFeature(mean, mean, 0.0)
        return CellFeature(mean, mean(inMode), inMode.size.toDouble() / unmasked.size)
    }

    fun mean(values: List<Lab>): Lab {
        require(values.isNotEmpty()) { "No values to average" }
        return Lab(values.sumOf { it.l } / values.size, values.sumOf { it.a } / values.size, values.sumOf { it.b } / values.size)
    }
}
