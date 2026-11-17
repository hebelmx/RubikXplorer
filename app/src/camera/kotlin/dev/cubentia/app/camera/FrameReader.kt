package dev.cubentia.app.camera

import androidx.camera.core.ImageProxy
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.CellReadResult
import dev.cubentia.domain.recognition.CellReader
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.FrameQuality
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.Lab
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

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
 * One frame read at its located [lattice] (Story 10.14), with the lattice found [outside] the tolerance when that one
 * gave the stats' framing. For the debug diagnostics; numbers only.
 */
internal data class FrameReading(
    val stats: FrameStats,
    val lattice: Lattice,
    val outside: Lattice? = null,
)

/**
 * Reads one analysis frame into [FrameStats] (Stories 10.2, 10.14). When the drawn grid does not hold a cube face, the
 * face's 3×3 lattice is located inside it by [FaceLocator] (a face about 70–100 % of the grid's size, offset up to about
 * half a cell; see [locate]), and everything below — cells, glare, light, sharpness and seams — is read on that lattice
 * ("the grid" below). The grid is the region under the drawn grid
 * ([ViewfinderGrid.inFrame]; by default the centered square 80 % of the crop's shorter side, as in the fixture
 * recorder); each cell's central 60 % is sampled on a regular 12×12 lattice, converted YUV → sRGB → CIELAB and read by
 * [CellReader] — on a stickered black-body cube only its sticker samples, near-black body left out (see
 * [stickerSamples]). Mean luma comes from the Y plane inside the grid on a
 * [STATS_STEP]-pixel lattice. Sharpness (variance of the 4-neighbor Laplacian of Y) is taken on the same lattice but only
 * within ±[SEAM_BAND] cells of the four inner grid lines, where a face's edges are: a stickerless face's flat cells add
 * no edges and would read as blurred (device check 2026-11-16). Glare is a highlight, not a white sticker (device check
 * 2026-11-16): a cell is a white sticker when its unclipped samples (Y below [FrameQuality.GLARE_LUMA]) are bright and
 * near-neutral, or there are none, and when at least [CellReader.PLAIN_SHARE] of it is clipped it reads plain. Bright
 * near-neutral samples never count as glare by themselves, also when a cell mixes a white sticker with black seam or body
 * (PO 2026-11-16): glare is only a specular spot, a cell's clipped near-neutral samples (C*ab ≤ [WHITE_MAX_CHROMA]) when
 * none of them lies on the sampled lattice's outer ring (a compact spot inside the cell, not a white sticker reaching in
 * from the side) and at least [GLARE_SURROUND_SHARE] of the cell's other samples are a saturated sticker color (C*ab ≥
 * [GLARE_MIN_CHROMA]). The glare fraction is the largest such spot's share of its cell. Seams: each of the 12 inner
 * cell edges' central [CELL_ROI_FRACTION] is split into [SEAM_SEGMENTS] short segments (so a slightly tilted cube still
 * lines up within each); a segment's mean Y is profiled across a band of ±[SEAM_BAND] cells around the grid line (the
 * real seam is rarely exactly on it), and the segment dips when the profile's darkest point is at most [SEAM_RATIO] of,
 * and at least [MIN_SEAM_DEPTH] below, the darker of its two cells' mean Y (the dark gap between stickers or pieces).
 * The edge has a seam when at least [MIN_SEAM_SEGMENTS] of its segments dip, or when its two cells' readings differ by
 * at least [FrameQuality.COLOR_STEP_DELTA_E] and both are sticker evidence ([FrameQuality.isStickerEvidence]: a color
 * step between gapless stickerless pieces, PO 2026-11-16, never one onto dark background or body).
 * Sharpness is the larger of that near the inner lines and that near the outer edge (a solved gapless face's only
 * edge). Face edges: see [faceEdges]. Frames are read and dropped: nothing here
 * keeps pixels.
 */
internal object FrameReader {
    const val GRID_FRACTION = 0.8
    const val CELL_ROI_FRACTION = 0.6
    const val LATTICE = 12
    const val STATS_STEP = 2

    /** Half-width of the band profiled across each inner grid line, in cells. Provisional (device session B). */
    const val SEAM_BAND = 0.2

    /**
     * A seam's darkest profile point is at most this fraction of the darker cell's mean Y: a stickerless cube's thin
     * gaps blur to a shallow dip (device check 2026-11-16). Provisional (session B).
     */
    const val SEAM_RATIO = 0.85

    /** Segments each inner edge is split into, and how many of them must dip for a seam. Provisional (session B). */
    const val SEAM_SEGMENTS = 3
    const val MIN_SEAM_SEGMENTS = 2

    /** A seam is at least this much darker (Y, 0–255) than the darker cell. Provisional (session B). */
    const val MIN_SEAM_DEPTH = 8.0

    /** A white sticker's unclipped samples are at least this light (CIELAB L*). Provisional (session B). */
    const val WHITE_MIN_L = 80.0

    /** A white sticker's unclipped samples are at most this chromatic (CIELAB C*ab). Provisional (session B). */
    const val WHITE_MAX_CHROMA = 20.0

    /** A glare spot's surround is a saturated sticker at least this chromatic (CIELAB C*ab). Provisional (session B). */
    const val GLARE_MIN_CHROMA = 25.0

    /** At least this share of a cell's samples outside the spot must be saturated for the spot to be glare. */
    const val GLARE_SURROUND_SHARE = 0.6

    /** Black cube body: at most this light (CIELAB L*) and this chromatic (C*ab). Provisional (session B). */
    const val BODY_MAX_L = 30.0
    const val BODY_MAX_CHROMA = 15.0

    /** Samples within this CIE76 ΔE of one another are one color for a center's dominant color. Provisional (session B). */
    const val DOMINANT_DELTA_E = 10.0

    /** A face edge: the band outside a side, in cells from the side, and its samples across. Provisional (session B). */
    const val FACE_EDGE_FROM = 0.1
    const val FACE_EDGE_TO = 0.35
    const val FACE_EDGE_SAMPLES = 4

    /** A side's cell contrasts with the band outside it by at least this CIE76 ΔE. Provisional (session B). */
    const val FACE_EDGE_DELTA_E = 20.0

    /** At least this many of a side's three cells must contrast for the side to be a face edge. */
    const val MIN_FACE_EDGE_SEGMENTS = 2

    /** A cell's body samples are left out only when at least this share of its samples are sticker. */
    const val MIN_STICKER_SHARE = 0.25

    /**
     * Reads [image] at the face located inside [grid] (frame pixels; see [ViewfinderGrid.inFrame]), whose
     * [rotationDegrees] turn it upright for the Learner.
     */
    fun locate(
        image: ImageProxy,
        grid: GridSquare,
        rotationDegrees: Int,
    ): FrameReading {
        val crop = image.cropRect
        val planes = image.planes

        fun plane(index: Int) = YuvPlane(planes[index].buffer, planes[index].rowStride, planes[index].pixelStride)
        return locate(YuvFrame(plane(0), plane(1), plane(2), crop.left, crop.top, crop.right, crop.bottom), grid, rotationDegrees)
    }

    /** Reads [frame] at the face located inside [grid]; see [locate]. */
    fun read(
        frame: YuvFrame,
        grid: GridSquare? = null,
        rotationDegrees: Int = 0,
    ): FrameStats = locate(frame, grid, rotationDegrees).stats

    /**
     * Reads [frame] inside [grid] — by default the centered square of [GRID_FRACTION] of the crop's shorter side — and,
     * when that does not read as a cube face (MISALIGNED), locates the face's lattice with [FaceLocator] (Story 10.14):
     * the best-ranked tolerated lattices are read ([readAt]) in order and the first ACCEPTABLE one is the frame's
     * reading. When none is, the fixed grid's reading stands, and the wider search looks for a face outside the
     * tolerance; when one reads ACCEPTABLE, the stats carry its [FrameStats.framing] for the Learner, whose preview is the
     * frame turned [rotationDegrees] clockwise. [FrameReading.lattice] is the lattice read, or the best-scoring candidate
     * when none was accepted (for the diagnostics).
     */
    fun locate(
        frame: YuvFrame,
        grid: GridSquare? = null,
        rotationDegrees: Int = 0,
    ): FrameReading {
        val exact = Lattice(1.0, 0.0, 0.0, 0.0)
        if (frame.width <= 0 || frame.height <= 0) return FrameReading(empty(), exact)
        val square = grid ?: ViewfinderGrid.inFrame(0.0, 0.0, frame.left, frame.top, frame.right, frame.bottom, 0)
        if (square.side <= 0) return FrameReading(empty(), exact)
        val stats = readAt(frame, square)
        if (FrameQuality.assess(stats) != CaptureQuality.MISALIGNED) return FrameReading(stats, exact)
        val map = FaceLocator.LumaMap(frame, square)

        /** The first of [ranked] that reads as a cube face, with its stats. */
        fun firstFace(ranked: List<Lattice>): Pair<Lattice, FrameStats>? =
            ranked.firstNotNullOfOrNull { lattice ->
                val located = readAt(frame, lattice.square(square))
                (lattice to located).takeIf { FrameQuality.assess(located) == CaptureQuality.ACCEPTABLE }
            }
        val ranked = FaceLocator.rank(map, FaceLocator.tolerated)
        firstFace(ranked)?.let { (lattice, located) -> return FrameReading(located, lattice) }
        val outside = firstFace(FaceLocator.rank(map, FaceLocator.wide))?.first
        return FrameReading(stats.copy(framing = outside?.framing(rotationDegrees)), ranked.first(), outside)
    }

    fun centerRing(
        image: ImageProxy,
        square: GridSquare,
    ): CellReading? {
        val crop = image.cropRect
        val planes = image.planes

        fun plane(index: Int) = YuvPlane(planes[index].buffer, planes[index].rowStride, planes[index].pixelStride)
        return centerRing(YuvFrame(plane(0), plane(1), plane(2), crop.left, crop.top, crop.right, crop.bottom), square)
    }

    fun centerRing(
        frame: YuvFrame,
        square: GridSquare,
    ): CellReading? {
        if (frame.width <= 0 || frame.height <= 0 || square.side <= 0) return null
        val cell = square.side / 3
        val positions = mutableListOf<Int>()
        val clipped = mutableListOf<Boolean>()
        val pixels =
            (0 until LATTICE * LATTICE).mapNotNull { point ->
                val dx = 0.1 + (point % LATTICE + 0.5) * 0.8 / LATTICE
                val dy = 0.1 + (point / LATTICE + 0.5) * 0.8 / LATTICE
                if (dx in 0.25..0.75 && dy in 0.25..0.75) return@mapNotNull null
                val x = (square.left + cell * (1 + dx)).toInt()
                val y = (square.top + cell * (1 + dy)).toInt()
                if (x < frame.left || x >= frame.right || y < frame.top || y >= frame.bottom) return@mapNotNull null
                positions += point
                clipped += frame.y.at(x, y) >= FrameQuality.GLARE_LUMA
                lab(frame, x, y)
            }
        // Body removal judges each sample by its place on the 12×12 lattice, not by its index among the ring samples.
        val sticker = stickerSamples(pixels, positions)
        val reading = (CellReader.read(sticker.map { pixels[it] }) as? CellReadResult.Read)?.reading ?: return null
        // A clipped white sticker is all masked by the reader, yet it is one plain color (as in [readAt]).
        val clippedShare = sticker.count { clipped[it] } / sticker.size.toDouble()
        val white = isWhite(sticker.filter { !clipped[it] }.map { pixels[it] })
        return if (white && clippedShare >= CellReader.PLAIN_SHARE) reading.copy(plain = true) else reading
    }

    private fun empty() = FrameStats(List(FrameQuality.CELLS) { null }, 0.0, 0.0, 0.0, 0)

    /** Reads [frame] at the lattice [square] (frame pixels): see the class KDoc. */
    fun readAt(
        frame: YuvFrame,
        square: GridSquare,
    ): FrameStats {
        if (frame.width <= 0 || frame.height <= 0) return empty()
        val side = square.side
        if (side <= 0) return empty()
        val gridLeft = square.left
        val gridTop = square.top
        val cell = side / 3
        var glare = 0.0
        val cells =
            (0 until FrameQuality.CELLS).map { index ->
                val roiLeft = gridLeft + (index % 3) * cell + cell * (1 - CELL_ROI_FRACTION) / 2
                val roiTop = gridTop + (index / 3) * cell + cell * (1 - CELL_ROI_FRACTION) / 2
                val step = cell * CELL_ROI_FRACTION / LATTICE
                val clipped = mutableListOf<Boolean>()
                val pixels =
                    (0 until LATTICE * LATTICE).map { point ->
                        val x = (roiLeft + (point % LATTICE + 0.5) * step).toInt().coerceIn(frame.left, frame.right - 1)
                        val y = (roiTop + (point / LATTICE + 0.5) * step).toInt().coerceIn(frame.top, frame.bottom - 1)
                        clipped += frame.y.at(x, y) >= FrameQuality.GLARE_LUMA
                        lab(frame, x, y)
                    }
                glare = maxOf(glare, specularShare(pixels, clipped))
                // A stickered black-body cube: the sticker alone is read when black body shows around it.
                val sticker = stickerSamples(pixels)
                val clippedShare = sticker.count { clipped[it] } / sticker.size.toDouble()
                val white = isWhite(sticker.filter { !clipped[it] }.map { pixels[it] })
                val clippedWhite = clippedWhiteSticker(pixels, clipped, sticker)
                (CellReader.read(sticker.map { pixels[it] }) as? CellReadResult.Read)?.reading?.let { reading ->
                    when {
                        // A clipped white sticker is all masked by the reader, yet it is one plain color.
                        white && clippedShare >= CellReader.PLAIN_SHARE -> {
                            reading.copy(plain = true)
                        }

                        // A clipped white sticker around a logo: the reader would see only the logo.
                        clippedWhite != null -> {
                            clippedWhite
                        }

                        // A center that is not plain may carry a logo: read the sticker's dominant color.
                        index == FaceSample.CENTER_INDEX && !reading.plain -> {
                            dominant(sticker.map { pixels[it] })?.let { reading.copy(value = it) } ?: reading
                        }

                        else -> {
                            reading
                        }
                    }
                }
            }
        val seams = seams(frame, gridLeft, gridTop, cell, cells)
        val faceEdges = faceEdges(frame, gridLeft, gridTop, cell, cells)
        return stats(frame, cells, gridLeft, gridTop, side).copy(glareFraction = glare, seams = seams, faceEdges = faceEdges)
    }

    /**
     * The share of a cell's lattice [pixels] in a specular spot (see the class KDoc): its [clipped] near-neutral samples,
     * none on the lattice's outer ring, inside a saturated sticker; 0 when there is no such spot.
     */
    private fun specularShare(
        pixels: List<Lab>,
        clipped: List<Boolean>,
    ): Double {
        val spot = pixels.indices.filter { clipped[it] && chroma(pixels[it]) <= WHITE_MAX_CHROMA }
        if (spot.isEmpty()) return 0.0
        if (spot.any(::onOuterRing)) return 0.0
        val surround = pixels.indices.filter { !clipped[it] || chroma(pixels[it]) > WHITE_MAX_CHROMA }
        if (surround.isEmpty()) return 0.0
        val saturated = surround.count { chroma(pixels[it]) >= GLARE_MIN_CHROMA }
        return if (saturated >= GLARE_SURROUND_SHARE * surround.size) spot.size / pixels.size.toDouble() else 0.0
    }

    /**
     * The dominant color of a cell's [samples]: the mean of the largest group of samples within [DOMINANT_DELTA_E]
     * (CIE76) of one sample, when that group is a majority; null otherwise. A logo on a center is a minority, and unlike
     * the reader's histogram peak this is not split by bin edges (a white near a* = b* = 0 spreads over several bins).
     */
    private fun dominant(samples: List<Lab>): Lab? {
        fun near(
            first: Lab,
            second: Lab,
        ): Boolean {
            val dl = first.l - second.l
            val da = first.a - second.a
            val db = first.b - second.b
            return dl * dl + da * da + db * db <= DOMINANT_DELTA_E * DOMINANT_DELTA_E
        }
        val group = samples.map { seed -> samples.filter { near(seed, it) } }.maxByOrNull { it.size } ?: return null
        if (group.size * 2 <= samples.size) return null
        return Lab(group.sumOf { it.l } / group.size, group.sumOf { it.a } / group.size, group.sumOf { it.b } / group.size)
    }

    /**
     * The reading of a cell that is mostly a clipped white sticker around a logo (PO 2026-11-16): more than half of its
     * [sticker] samples, and most of those on the lattice's outer ring, are clipped near-neutral. The reader masks those
     * as glare and would read the logo; the cell's dominant color is the white, plain when it holds
     * [CellReader.PLAIN_SHARE] of the samples. Null when the cell is not such a sticker.
     */
    private fun clippedWhiteSticker(
        pixels: List<Lab>,
        clipped: List<Boolean>,
        sticker: List<Int>,
    ): CellReading? {
        fun clippedWhite(point: Int) = clipped[point] && chroma(pixels[point]) <= WHITE_MAX_CHROMA
        val whites = sticker.filter(::clippedWhite)
        if (whites.size * 2 <= sticker.size) return null
        val ring = sticker.filter(::onOuterRing)
        if (ring.count(::clippedWhite) * 2 <= ring.size) return null
        val value =
            Lab(
                whites.sumOf { pixels[it].l } / whites.size,
                whites.sumOf { pixels[it].a } / whites.size,
                whites.sumOf { pixels[it].b } / whites.size,
            )
        return CellReading(value, whites.size >= CellReader.PLAIN_SHARE * sticker.size)
    }

    /**
     * The lattice indices of a cell's sticker: every sample, unless the cell clearly holds a black border — some
     * near-black body samples (L* ≤ [BODY_MAX_L], C*ab ≤ [BODY_MAX_CHROMA]) on the lattice's outer ring and at least
     * [MIN_STICKER_SHARE] of the samples not body — when the body samples are left out.
     */
    private fun stickerSamples(
        pixels: List<Lab>,
        positions: List<Int> = pixels.indices.toList(),
    ): List<Int> {
        val all = pixels.indices.toList()
        val body = all.filter { pixels[it].l <= BODY_MAX_L && chroma(pixels[it]) <= BODY_MAX_CHROMA }
        if (body.isEmpty() || body.none { onOuterRing(positions[it]) }) return all
        val sticker = all - body.toSet()
        return if (sticker.size >= MIN_STICKER_SHARE * pixels.size) sticker else all
    }

    private fun onOuterRing(point: Int): Boolean {
        val edge = LATTICE - 1
        return point % LATTICE == 0 || point % LATTICE == edge || point / LATTICE == 0 || point / LATTICE == edge
    }

    private fun chroma(pixel: Lab): Double = sqrt(pixel.a * pixel.a + pixel.b * pixel.b)

    /** Whether a cell's [unclipped] samples are those of a white sticker: none at all, or bright and near-neutral. */
    private fun isWhite(unclipped: List<Lab>): Boolean {
        if (unclipped.isEmpty()) return true
        val l = unclipped.sumOf { it.l } / unclipped.size
        val a = unclipped.sumOf { it.a } / unclipped.size
        val b = unclipped.sumOf { it.b } / unclipped.size
        return l >= WHITE_MIN_L && sqrt(a * a + b * b) <= WHITE_MAX_CHROMA
    }

    /** How many of the 12 inner cell edges show a dark seam or a color step between [cells] (see the class KDoc). */
    private fun seams(
        frame: YuvFrame,
        gridLeft: Double,
        gridTop: Double,
        cell: Double,
        cells: List<CellReading?>,
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
        val segment = CELL_ROI_FRACTION * cell / SEAM_SEGMENTS
        val along = segment.toInt().coerceAtLeast(1)

        /** The darkest mean Y across the band, [lineAt] the grid line and [alongFrom] the segment's start. */
        fun valley(
            vertical: Boolean,
            lineAt: Double,
            alongFrom: Double,
        ): Double =
            (0..across).minOf { offset ->
                val crossAt = lineAt - SEAM_BAND * cell + offset
                (0 until along).sumOf { point ->
                    val at = alongFrom + point
                    if (vertical) luma(crossAt, at) else luma(at, crossAt)
                } / along.toDouble()
            }

        /** Whether the edge on [lineAt], whose central part starts at [alongFrom], parts cells [first] and [second]. */
        fun seam(
            vertical: Boolean,
            lineAt: Double,
            alongFrom: Double,
            first: Int,
            second: Int,
        ): Boolean {
            val a = cells[first]?.takeIf(FrameQuality::isStickerEvidence)?.value
            val b = cells[second]?.takeIf(FrameQuality::isStickerEvidence)?.value
            if (a != null && b != null && deltaE(a, b) >= FrameQuality.COLOR_STEP_DELTA_E) return true
            val darker = minOf(cellLuma[first], cellLuma[second])
            val dips =
                (0 until SEAM_SEGMENTS).count { index ->
                    val valley = valley(vertical, lineAt, alongFrom + index * segment)
                    valley <= darker * SEAM_RATIO && darker - valley >= MIN_SEAM_DEPTH
                }
            return dips >= MIN_SEAM_SEGMENTS
        }
        val margin = cell * (1 - CELL_ROI_FRACTION) / 2
        var count = 0
        for (k in 0..2) {
            for (line in 1..2) {
                // Row k between columns line - 1 and line, then column k between rows line - 1 and line.
                if (seam(true, gridLeft + line * cell, gridTop + k * cell + margin, k * 3 + line - 1, k * 3 + line)) count++
                if (seam(false, gridTop + line * cell, gridLeft + k * cell + margin, (line - 1) * 3 + k, line * 3 + k)) count++
            }
        }
        return count
    }

    /** Mean luma over the grid and sharpness near its inner lines (see the class KDoc); glare and seams are set apart. */
    private fun stats(
        frame: YuvFrame,
        cells: List<CellReading?>,
        gridLeft: Double,
        gridTop: Double,
        side: Double,
    ): FrameStats {
        val cell = side / 3
        val band = SEAM_BAND * cell

        fun nearLine(
            at: Int,
            start: Double,
        ) = abs(at - (start + cell)) <= band || abs(at - (start + 2 * cell)) <= band

        // Laplacian neighbors stay inside the crop.
        val x0 = maxOf(gridLeft.toInt(), frame.left + 1)
        val y0 = maxOf(gridTop.toInt(), frame.top + 1)
        val x1 = minOf(gridLeft.toInt() + side.toInt(), frame.right - 1)
        val y1 = minOf(gridTop.toInt() + side.toInt(), frame.bottom - 1)
        var count = 0
        var lumaSum = 0.0
        var edges = 0
        var lapSum = 0.0
        var lapSquares = 0.0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val luma = frame.y.at(x, y)
                lumaSum += luma
                count++
                if (nearLine(x, gridLeft) || nearLine(y, gridTop)) {
                    val laplacian =
                        (4 * luma - frame.y.at(x - 1, y) - frame.y.at(x + 1, y) - frame.y.at(x, y - 1) - frame.y.at(x, y + 1)).toDouble()
                    lapSum += laplacian
                    lapSquares += laplacian * laplacian
                    edges++
                }
                x += STATS_STEP
            }
            y += STATS_STEP
        }
        if (count == 0) return FrameStats(cells, 0.0, 0.0, 0.0, 0)
        val sharpness = if (edges == 0) 0.0 else (lapSum / edges).let { lapSquares / edges - it * it }
        return FrameStats(cells, lumaSum / count, 0.0, maxOf(sharpness, outerSharpness(frame, gridLeft, gridTop, side)), 0)
    }

    /**
     * The variance of the Laplacian of Y within ±[SEAM_BAND] cells of the lattice's outer edge, on the [STATS_STEP]
     * lattice: a solved gapless face has no edges near its inner lines, only its outline (PO 2026-11-16).
     */
    private fun outerSharpness(
        frame: YuvFrame,
        left: Double,
        top: Double,
        side: Double,
    ): Double {
        val band = SEAM_BAND * side / 3
        val right = left + side
        val bottom = top + side
        val x0 = maxOf((left - band).toInt(), frame.left + 1)
        val y0 = maxOf((top - band).toInt(), frame.top + 1)
        val x1 = minOf((right + band).toInt() + 1, frame.right - 1)
        val y1 = minOf((bottom + band).toInt() + 1, frame.bottom - 1)
        var edges = 0
        var lapSum = 0.0
        var lapSquares = 0.0
        var y = y0
        while (y < y1) {
            val nearRow = abs(y - top) <= band || abs(y - bottom) <= band
            var x = x0
            while (x < x1) {
                if (nearRow || abs(x - left) <= band || abs(x - right) <= band) {
                    val laplacian =
                        (
                            4 *
                                frame.y.at(
                                    x,
                                    y,
                                ) - frame.y.at(x - 1, y) - frame.y.at(x + 1, y) - frame.y.at(x, y - 1) - frame.y.at(x, y + 1)
                        ).toDouble()
                    lapSum += laplacian
                    lapSquares += laplacian * laplacian
                    edges++
                }
                x += STATS_STEP
            }
            y += STATS_STEP
        }
        return if (edges == 0) 0.0 else (lapSum / edges).let { lapSquares / edges - it * it }
    }

    /**
     * How many of the lattice's four sides contrast with what lies just outside them: on at least
     * [MIN_FACE_EDGE_SEGMENTS] of a side's three cells, the mean color of a band [FACE_EDGE_FROM]–[FACE_EDGE_TO] cells
     * outside, over the cell's central [CELL_ROI_FRACTION], is at least [FACE_EDGE_DELTA_E] (CIE76) from the cell's
     * reading. Band samples outside the crop are left out; a band with none has no contrast.
     */
    private fun faceEdges(
        frame: YuvFrame,
        left: Double,
        top: Double,
        cell: Double,
        cells: List<CellReading?>,
    ): Int {
        val margin = cell * (1 - CELL_ROI_FRACTION) / 2

        /** The band's mean color at across-offsets [from]..[to] beyond the side, or null when it is outside the crop. */
        fun band(
            vertical: Boolean,
            lineAt: Double,
            outward: Double,
            alongFrom: Double,
        ): Lab? {
            var sumY = 0.0
            var sumU = 0.0
            var sumV = 0.0
            var count = 0
            for (across in 0 until FACE_EDGE_SAMPLES) {
                val offset =
                    lineAt + outward * cell * (FACE_EDGE_FROM + (FACE_EDGE_TO - FACE_EDGE_FROM) * (across + 0.5) / FACE_EDGE_SAMPLES)
                for (along in 0 until LATTICE) {
                    val at = alongFrom + cell * CELL_ROI_FRACTION * (along + 0.5) / LATTICE
                    val x = (if (vertical) offset else at).toInt()
                    val y = (if (vertical) at else offset).toInt()
                    if (x < frame.left || x >= frame.right || y < frame.top || y >= frame.bottom) continue
                    sumY += frame.y.at(x, y)
                    sumU += frame.u.at(x / 2, y / 2)
                    sumV += frame.v.at(x / 2, y / 2)
                    count++
                }
            }
            if (count == 0) return null
            return labOf(sumY / count, sumU / count - CHROMA_OFFSET, sumV / count - CHROMA_OFFSET)
        }

        fun side(
            vertical: Boolean,
            lineAt: Double,
            outward: Double,
            indices: List<Int>,
        ): Boolean =
            indices.withIndex().count { (segment, index) ->
                val reading = cells[index]?.value ?: return@count false
                val start = (if (vertical) top else left) + segment * cell + margin
                val outside = band(vertical, lineAt, outward, start) ?: return@count false
                deltaE(reading, outside) >= FACE_EDGE_DELTA_E
            } >= MIN_FACE_EDGE_SEGMENTS
        return listOf(
            side(true, left, -1.0, listOf(0, 3, 6)),
            side(true, left + 3 * cell, 1.0, listOf(2, 5, 8)),
            side(false, top, -1.0, listOf(0, 1, 2)),
            side(false, top + 3 * cell, 1.0, listOf(6, 7, 8)),
        ).count { it }
    }

    private fun deltaE(
        first: Lab,
        second: Lab,
    ): Double {
        val dl = first.l - second.l
        val da = first.a - second.a
        val db = first.b - second.b
        return sqrt(dl * dl + da * da + db * db)
    }

    private fun lab(
        frame: YuvFrame,
        x: Int,
        y: Int,
    ): Lab {
        val yy = frame.y.at(x, y).toDouble()
        val uu = frame.u.at(x / 2, y / 2) - CHROMA_OFFSET
        val vv = frame.v.at(x / 2, y / 2) - CHROMA_OFFSET
        return labOf(yy, uu, vv)
    }

    /** Full-range BT.601 Y and centered U, V (Cb, Cr) to CIELAB. */
    private fun labOf(
        yy: Double,
        uu: Double,
        vv: Double,
    ): Lab {
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
