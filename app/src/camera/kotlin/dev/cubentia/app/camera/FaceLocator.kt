package dev.cubentia.app.camera

import dev.cubentia.domain.recognition.Framing
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A 3×3 lattice located in the drawn grid (Story 10.14, PO 2026-11-16): its side is [scale] times the grid's, and its
 * center sits [dx], [dy] grid cells from the grid's center in frame axes. [score] is the search's score (higher fits a
 * cube face better; see [FaceLocator]).
 */
internal data class Lattice(
    val scale: Double,
    val dx: Double,
    val dy: Double,
    val score: Double,
) {
    /** The face fills the grid exactly: the fixed grid itself is read. */
    val exact: Boolean get() = scale == 1.0 && dx == 0.0 && dy == 0.0

    /** The lattice's square in frame pixels, for [grid]; the grid itself when [exact]. */
    fun square(grid: GridSquare): GridSquare {
        if (exact) return grid
        val cell = grid.side / 3
        val side = grid.side * scale
        return GridSquare(grid.left + grid.side / 2 + dx * cell - side / 2, grid.top + grid.side / 2 + dy * cell - side / 2, side)
    }

    /**
     * How the Learner should move a face found here, outside the tolerance, as they see the preview: the frame turned
     * upright by [rotationDegrees] clockwise (the back camera's preview is not mirrored). An offset beyond
     * [FaceLocator.MAX_OFFSET] is corrected first, toward the grid's center; otherwise a face smaller than
     * [FaceLocator.MIN_SCALE] is brought closer.
     */
    fun framing(rotationDegrees: Int): Framing {
        val (upX, upY) =
            when (Math.floorMod(rotationDegrees, FULL_TURN)) {
                QUARTER_TURN -> -dy to dx
                HALF_TURN -> -dx to -dy
                THREE_QUARTER_TURN -> dy to -dx
                else -> dx to dy
            }
        val offset = max(abs(upX), abs(upY)) > FaceLocator.MAX_OFFSET + EPSILON
        return when {
            !offset && scale < FaceLocator.MIN_SCALE - EPSILON -> Framing.CLOSER
            abs(upX) >= abs(upY) -> if (upX > 0) Framing.LEFT else Framing.RIGHT
            else -> if (upY > 0) Framing.UP else Framing.DOWN
        }
    }

    private companion object {
        const val QUARTER_TURN = 90
        const val HALF_TURN = 180
        const val THREE_QUARTER_TURN = 270
        const val FULL_TURN = 360
        const val EPSILON = 1e-9
    }
}

/**
 * Finds a cube face's 3×3 lattice in the drawn grid (Story 10.14, PO 2026-11-16): a beginner holding the cube by hand
 * rarely fits it to the grid exactly. A deterministic exhaustive search over candidate lattices — scales [MIN_SCALE]–1
 * of the grid's side in steps of [SCALE_STEP], center offsets up to ±[MAX_OFFSET] cells in steps of [OFFSET_STEP] — on a
 * [LumaMap] of the grid's surroundings, each candidate scored as
 *
 * - **seams**: per inner edge, how much darker a thin strip on the line is than the bands either side (a dark gap
 *   between stickers or pieces);
 * - **outline**: per outer edge segment, the YUV contrast between the band just inside and the band just outside the
 *   lattice (the face against its surroundings: this places a gapless face, which has no dark seams);
 * - **minus non-uniformity**: per cell, the YUV standard deviation over its central [UNIFORM_ROI] (a lattice off the
 *   stickers straddles two colors or a seam; this also places color steps between gapless pieces);
 * - **minus** [DISPLACEMENT_PENALTY] per cell of offset or of side lost, so that among equal fits the smallest move
 *   wins.
 *
 * The search runs only when the exact grid does not read as a cube face (MISALIGNED), so a face that fills the grid
 * reads exactly as before; the [RANKED] best candidates are then read in order and the first that is a cube face wins.
 *
 * [wide] candidates (scales down to [WIDE_MIN_SCALE], offsets up to ±[WIDE_MAX_OFFSET]) are searched only when the
 * tolerated face is not a cube face, to coach the Learner toward one that is clearly present outside the tolerance.
 *
 * Cost: a frame whose fixed grid reads as a cube face pays nothing extra. Otherwise the map reads about
 * (grid + 2 × [MAP_EXTENT] cells)² / stride² luma samples ([MAP_SAMPLES_PER_CELL] per cell; every pixel on the Lenovo's
 * ≈ 53 px cells, every second one on larger cells) into six integral images of about 100² entries; each candidate
 * costs about 60 constant-time box sums, and each ranked candidate read one ordinary frame read. Measured by
 * `FaceLocatorTest` on the JVM test machine, the worst case (no face anywhere: fixed read, tolerated search of 847
 * candidates, wide search, 2 × [RANKED] candidate reads) is about 6–7 ms for the Lenovo's 200×640 and the S24 FE's
 * 480×422 crops, against about 0.5 ms for one fixed-grid read; on the Lenovo's ARM cores, estimated at 5–10× slower,
 * that is roughly 30–70 ms per misaligned frame and none on an aligned one. The analyzer runs off the main thread and
 * keeps only the latest frame, so a slow frame only lowers the misaligned analysis rate.
 */
internal object FaceLocator {
    const val MIN_SCALE = 0.7
    const val MAX_OFFSET = 0.5
    const val SCALE_STEP = 0.05
    const val OFFSET_STEP = 0.1

    const val WIDE_MIN_SCALE = 0.45
    const val WIDE_MAX_OFFSET = 1.2
    const val WIDE_OFFSET_STEP = 0.2

    /** The map covers the grid and this many cells around it: the widest offset plus the outer band. */
    const val MAP_EXTENT = WIDE_MAX_OFFSET + 0.4

    /** Map samples per grid cell. */
    const val MAP_SAMPLES_PER_CELL = 16

    /** Each cell's central share judged for uniformity. */
    const val UNIFORM_ROI = 0.8

    /** Half-width of the strip on an inner line, and the bands either side of it (from, to), in lattice cells. */
    const val SEAM_STRIP = 0.06
    const val SIDE_BAND_FROM = 0.1
    const val SIDE_BAND_TO = 0.3

    /** The bands inside and outside an outer edge (from, to), in lattice cells. */
    const val OUTER_BAND_FROM = 0.05
    const val OUTER_BAND_TO = 0.25

    /** How many of the best-scoring candidates are read, in order, until one is a cube face. */
    const val RANKED = 4

    /** Score lost per grid cell of offset, or of the lattice's side lost (3 × (1 − scale)). */
    const val DISPLACEMENT_PENALTY = 5.0

    /** The tolerated candidates: (scale, dx, dy), the exact grid first. */
    val tolerated: List<Triple<Double, Double, Double>> = candidates(MIN_SCALE, SCALE_STEP, MAX_OFFSET, OFFSET_STEP)

    /** The candidates outside the tolerance, for coaching. */
    val wide: List<Triple<Double, Double, Double>> =
        candidates(WIDE_MIN_SCALE, SCALE_STEP, WIDE_MAX_OFFSET, WIDE_OFFSET_STEP).filter { (scale, dx, dy) ->
            scale < MIN_SCALE - 1e-9 || abs(dx) > MAX_OFFSET + 1e-9 || abs(dy) > MAX_OFFSET + 1e-9
        }

    private fun candidates(
        minScale: Double,
        scaleStep: Double,
        maxOffset: Double,
        offsetStep: Double,
    ): List<Triple<Double, Double, Double>> {
        val scales = (0..((1.0 - minScale) / scaleStep + 1e-9).toInt()).map { 1.0 - it * scaleStep }
        val steps = (maxOffset / offsetStep + 1e-9).toInt()
        val offsets = (0..steps).flatMap { if (it == 0) listOf(0.0) else listOf(it * offsetStep, -it * offsetStep) }
        return scales.flatMap { scale -> offsets.flatMap { dy -> offsets.map { dx -> Triple(scale, dx, dy) } } }
    }

    /** The [count] best-scoring of [candidates] on [map], best first (ties keep the candidates' order). */
    fun rank(
        map: LumaMap,
        candidates: List<Triple<Double, Double, Double>>,
        count: Int = RANKED,
    ): List<Lattice> {
        val best = ArrayList<Lattice>(count + 1)
        for ((scale, dx, dy) in candidates) {
            val score = score(map, scale, dx, dy)
            if (best.size == count && score <= best.last().score) continue
            val at = best.indexOfFirst { score > it.score }.let { if (it < 0) best.size else it }
            best.add(at, Lattice(scale, dx, dy, score))
            if (best.size > count) best.removeAt(best.size - 1)
        }
        return best
    }

    /** The candidate's score (see the class KDoc). */
    fun score(
        map: LumaMap,
        scale: Double,
        dx: Double,
        dy: Double,
    ): Double {
        val grid = map.grid
        val cell = grid.side / 3
        val k = cell * scale
        val left = grid.left + grid.side / 2 + dx * cell - 1.5 * k
        val top = grid.top + grid.side / 2 + dy * cell - 1.5 * k
        var score = -DISPLACEMENT_PENALTY * (abs(dx) + abs(dy) + 3 * (1 - scale))
        val margin = k * (1 - UNIFORM_ROI) / 2
        for (row in 0..2) {
            for (column in 0..2) {
                val x0 = left + column * k + margin
                val y0 = top + row * k + margin
                score -= map.deviation(x0, y0, x0 + k * UNIFORM_ROI, y0 + k * UNIFORM_ROI)
            }
        }
        val alongMargin = k * 0.2
        for (line in 1..2) {
            for (k3 in 0..2) {
                // Vertical line between columns line - 1 and line, in row k3; then horizontal, in column k3.
                val x = left + line * k
                val a0 = top + k3 * k + alongMargin
                val a1 = top + (k3 + 1) * k - alongMargin
                val strip = map.meanY(x - SEAM_STRIP * k, a0, x + SEAM_STRIP * k, a1)
                val before = map.meanY(x - SIDE_BAND_TO * k, a0, x - SIDE_BAND_FROM * k, a1)
                val after = map.meanY(x + SIDE_BAND_FROM * k, a0, x + SIDE_BAND_TO * k, a1)
                score += max(0.0, minOf(before, after) - strip)
                val y = top + line * k
                val b0 = left + k3 * k + alongMargin
                val b1 = left + (k3 + 1) * k - alongMargin
                val hStrip = map.meanY(b0, y - SEAM_STRIP * k, b1, y + SEAM_STRIP * k)
                val above = map.meanY(b0, y - SIDE_BAND_TO * k, b1, y - SIDE_BAND_FROM * k)
                val below = map.meanY(b0, y + SIDE_BAND_FROM * k, b1, y + SIDE_BAND_TO * k)
                score += max(0.0, minOf(above, below) - hStrip)
            }
        }
        val right = left + 3 * k
        val bottom = top + 3 * k
        val near = OUTER_BAND_FROM * k
        val far = OUTER_BAND_TO * k
        for (segment in 0..2) {
            val a0 = top + segment * k + alongMargin
            val a1 = top + (segment + 1) * k - alongMargin
            score += map.contrast(left + near, a0, left + far, a1, left - far, a0, left - near, a1)
            score += map.contrast(right - far, a0, right - near, a1, right + near, a0, right + far, a1)
            val b0 = left + segment * k + alongMargin
            val b1 = left + (segment + 1) * k - alongMargin
            score += map.contrast(b0, top + near, b1, top + far, b0, top - far, b1, top - near)
            score += map.contrast(b0, bottom - far, b1, bottom - near, b0, bottom + near, b1, bottom + far)
        }
        return score
    }

    /**
     * The frame around a drawn [grid], downsampled to [MAP_SAMPLES_PER_CELL] samples per grid cell over the grid and
     * [MAP_EXTENT] cells around it: each sample is the mean Y of its block (every pixel, or every second one on blocks
     * of 4 px or more, so a thin seam still darkens it) and U, V at the block's center, kept as integral images of each
     * channel and its square for constant-time box means and variances. Positions outside the crop read its nearest
     * pixel. Numbers only; the frame is not kept.
     */
    class LumaMap(
        frame: YuvFrame,
        val grid: GridSquare,
    ) {
        private val pitch: Int = max(1, (grid.side / 3 / MAP_SAMPLES_PER_CELL).toInt())
        private val originX: Double = grid.left - MAP_EXTENT * grid.side / 3
        private val originY: Double = grid.top - MAP_EXTENT * grid.side / 3
        private val size: Int = ceil((grid.side + 2 * MAP_EXTENT * grid.side / 3) / pitch).toInt().coerceAtLeast(1)
        private val stride = (size + 1)
        private val sumY = DoubleArray(stride * stride)
        private val sumYY = DoubleArray(stride * stride)
        private val sumU = DoubleArray(stride * stride)
        private val sumUU = DoubleArray(stride * stride)
        private val sumV = DoubleArray(stride * stride)
        private val sumVV = DoubleArray(stride * stride)

        init {
            val step = if (pitch >= 4) 2 else 1
            for (j in 0 until size) {
                val y0 = (originY + j * pitch).toInt()
                var rowY = 0.0
                var rowYY = 0.0
                var rowU = 0.0
                var rowUU = 0.0
                var rowV = 0.0
                var rowVV = 0.0
                for (i in 0 until size) {
                    val x0 = (originX + i * pitch).toInt()
                    var total = 0
                    var count = 0
                    var dy = 0
                    while (dy < pitch) {
                        val y = (y0 + dy).coerceIn(frame.top, frame.bottom - 1)
                        var dx = 0
                        while (dx < pitch) {
                            total += frame.y.at((x0 + dx).coerceIn(frame.left, frame.right - 1), y)
                            count++
                            dx += step
                        }
                        dy += step
                    }
                    val luma = total / count.toDouble()
                    val cx = (x0 + pitch / 2).coerceIn(frame.left, frame.right - 1)
                    val cy = (y0 + pitch / 2).coerceIn(frame.top, frame.bottom - 1)
                    val u = frame.u.at(cx / 2, cy / 2).toDouble()
                    val v = frame.v.at(cx / 2, cy / 2).toDouble()
                    rowY += luma
                    rowYY += luma * luma
                    rowU += u
                    rowUU += u * u
                    rowV += v
                    rowVV += v * v
                    val at = (j + 1) * stride + i + 1
                    val up = j * stride + i + 1
                    sumY[at] = sumY[up] + rowY
                    sumYY[at] = sumYY[up] + rowYY
                    sumU[at] = sumU[up] + rowU
                    sumUU[at] = sumUU[up] + rowUU
                    sumV[at] = sumV[up] + rowV
                    sumVV[at] = sumVV[up] + rowVV
                }
            }
        }

        private var i0 = 0
        private var j0 = 0
        private var i1 = 0
        private var j1 = 0

        /** Sets the map box covering frame box ([x0], [y0])–([x1], [y1]), at least one sample, clamped to the map. */
        private fun box(
            x0: Double,
            y0: Double,
            x1: Double,
            y1: Double,
        ): Double {
            i0 = floor((x0 - originX) / pitch).toInt().coerceIn(0, size - 1)
            j0 = floor((y0 - originY) / pitch).toInt().coerceIn(0, size - 1)
            i1 = ceil((x1 - originX) / pitch).toInt().coerceIn(i0 + 1, size)
            j1 = ceil((y1 - originY) / pitch).toInt().coerceIn(j0 + 1, size)
            return ((i1 - i0) * (j1 - j0)).toDouble()
        }

        private fun sum(table: DoubleArray): Double =
            table[j1 * stride + i1] - table[j0 * stride + i1] - table[j1 * stride + i0] + table[j0 * stride + i0]

        /** Mean Y over a frame box. */
        fun meanY(
            x0: Double,
            y0: Double,
            x1: Double,
            y1: Double,
        ): Double {
            val n = box(x0, y0, x1, y1)
            return sum(sumY) / n
        }

        /** The YUV standard deviation (root of the summed channel variances) over a frame box. */
        fun deviation(
            x0: Double,
            y0: Double,
            x1: Double,
            y1: Double,
        ): Double {
            val n = box(x0, y0, x1, y1)

            fun variance(
                values: DoubleArray,
                squares: DoubleArray,
            ): Double {
                val mean = sum(values) / n
                return max(0.0, sum(squares) / n - mean * mean)
            }
            return sqrt(variance(sumY, sumYY) + variance(sumU, sumUU) + variance(sumV, sumVV))
        }

        /** The YUV distance between the mean colors of two frame boxes. */
        fun contrast(
            ax0: Double,
            ay0: Double,
            ax1: Double,
            ay1: Double,
            bx0: Double,
            by0: Double,
            bx1: Double,
            by1: Double,
        ): Double {
            var n = box(ax0, ay0, ax1, ay1)
            val y = sum(sumY) / n
            val u = sum(sumU) / n
            val v = sum(sumV) / n
            n = box(bx0, by0, bx1, by1)
            val dy = y - sum(sumY) / n
            val du = u - sum(sumU) / n
            val dv = v - sum(sumV) / n
            return sqrt(dy * dy + du * du + dv * dv)
        }
    }
}
