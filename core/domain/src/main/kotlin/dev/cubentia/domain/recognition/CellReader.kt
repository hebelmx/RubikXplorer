package dev.cubentia.domain.recognition

import kotlin.math.abs
import kotlin.math.floor

/** One grid cell's measured color: the histogram mode, and whether that mode is plain (holds ≥ 60% of the cell). */
data class CellReading(
    val value: Lab,
    val plain: Boolean,
)

/** Why a cell has no reading. */
enum class CellReadFailure { NO_PIXELS, INVALID_PIXEL }

sealed interface CellReadResult {
    data class Read(
        val reading: CellReading,
    ) : CellReadResult

    data class Failed(
        val failure: CellReadFailure,
    ) : CellReadResult
}

/**
 * The OQ-7 decision K cell reader: the histogram mode of one cell's ROI pixels (already in CIELAB).
 *
 * Pixels with L ≥ [MASK_L] are specular glare and are masked. The rest go into a 3-D histogram (L 10 bins over
 * [0, 100], a and b 16 bins over [−128, 128]; out-of-range pixels are not binned). The peak bin (ties to the first in
 * L, a, b order) and its 26 neighbors form the mode; its value is their mean and its share is their count over the
 * unmasked pixels. When every pixel is masked there is no peak: the value is the mean of all pixels and the cell is not
 * plain (likewise, when no unmasked pixel falls in range, the value is the mean of the unmasked pixels, not plain).
 */
object CellReader {
    /** Pixels at or above this lightness are specular glare and are masked out. */
    const val MASK_L = 98.0

    /** A cell is plain when its mode holds at least this share of its unmasked pixels. */
    const val PLAIN_SHARE = 0.60

    private const val L_BINS = 10
    private const val AB_BINS = 16
    private const val L_MAX = 100.0
    private const val AB_RANGE = 128.0

    fun read(pixels: List<Lab>): CellReadResult {
        if (pixels.isEmpty()) return CellReadResult.Failed(CellReadFailure.NO_PIXELS)
        if (pixels.any { !it.l.isFinite() || !it.a.isFinite() || !it.b.isFinite() }) {
            return CellReadResult.Failed(CellReadFailure.INVALID_PIXEL)
        }
        val mode = mode(pixels)
        return CellReadResult.Read(CellReading(mode.value, mode.share >= PLAIN_SHARE))
    }

    /** The mode value and its share; the share stays inside the module (no numeric confidence crosses it). */
    internal data class Mode(
        val value: Lab,
        val share: Double,
    )

    /** Requires a non-empty list of finite pixels. */
    internal fun mode(pixels: List<Lab>): Mode {
        val unmasked = pixels.filter { it.l < MASK_L }.ifEmpty { pixels }
        val counts = IntArray(L_BINS * AB_BINS * AB_BINS)
        // The peak is taken over unmasked pixels only, so an all-glare cell has no peak and falls back to the mean.
        pixels.filter { it.l < MASK_L }.forEach { pixel -> bin(pixel)?.let { counts[it]++ } }
        val peak = counts.indices.maxBy { counts[it] }
        if (counts[peak] == 0) return Mode(mean(unmasked), 0.0)
        val peakL = peak / (AB_BINS * AB_BINS)
        val peakA = peak / AB_BINS % AB_BINS
        val peakB = peak % AB_BINS
        val inMode =
            unmasked.filter { pixel ->
                val bin = bin(pixel)
                bin != null &&
                    abs(bin / (AB_BINS * AB_BINS) - peakL) <= 1 &&
                    abs(bin / AB_BINS % AB_BINS - peakA) <= 1 &&
                    abs(bin % AB_BINS - peakB) <= 1
            }
        // A non-empty peak bin means unmasked holds those very pixels, so inMode is never empty here.
        return Mode(mean(inMode), inMode.size.toDouble() / unmasked.size)
    }

    private fun bin(lab: Lab): Int? {
        val l = floor(lab.l * L_BINS / L_MAX).toInt()
        val a = floor((lab.a + AB_RANGE) * AB_BINS / (2 * AB_RANGE)).toInt()
        val b = floor((lab.b + AB_RANGE) * AB_BINS / (2 * AB_RANGE)).toInt()
        return if (l in 0 until L_BINS && a in 0 until AB_BINS && b in 0 until AB_BINS) (l * AB_BINS + a) * AB_BINS + b else null
    }

    internal fun mean(values: List<Lab>): Lab =
        Lab(values.sumOf { it.l } / values.size, values.sumOf { it.a } / values.size, values.sumOf { it.b } / values.size)
}
