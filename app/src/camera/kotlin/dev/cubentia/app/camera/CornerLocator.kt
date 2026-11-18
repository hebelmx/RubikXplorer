package dev.cubentia.app.camera

import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.FrameQuality
import dev.cubentia.domain.recognition.FrameStats
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class CornerPoint(
    val x: Double,
    val y: Double,
) {
    operator fun plus(other: CornerPoint) = CornerPoint(x + other.x, y + other.y)

    operator fun minus(other: CornerPoint) = CornerPoint(x - other.x, y - other.y)

    operator fun times(scale: Double) = CornerPoint(x * scale, y * scale)
}

internal class CornerGuide(
    square: GridSquare,
    rotationDegrees: Int = 0,
) {
    private val center = CornerPoint(square.left + square.side / 2, square.top + square.side / 2)
    val radius = square.side / 2

    fun vertices(
        scale: Double = 1.0,
        roll: Double = 0.0,
    ): List<CornerPoint> =
        List(6) { index ->
            val angle = (-90 - index * 60 - rotation + roll) * PI / 180
            center + CornerPoint(cos(angle), sin(angle)) * (radius * scale)
        } + center

    private val rotation = rotationDegrees

    fun quads(points: List<CornerPoint>): List<List<CornerPoint>> {
        val (t, ul, ll, b, lr) = points
        val ur = points[5]
        val c = points[6]
        return listOf(listOf(t, ur, c, ul), listOf(ul, c, b, ll), listOf(c, ur, lr, b))
    }
}

/** Maps the unit square to a quadrilateral in TL, TR, BR, BL order. */
internal class Homography private constructor(
    private val origin: CornerPoint,
    private val horizontal: CornerPoint,
    private val vertical: CornerPoint,
    private val g: Double,
    private val h: Double,
) {
    fun apply(
        x: Double,
        y: Double,
    ): CornerPoint = (origin + horizontal * x + vertical * y) * (1 / (1 + g * x + h * y))

    companion object {
        fun from(quad: List<CornerPoint>): Homography? {
            val (a, b, c, d) = quad
            val u = b - c
            val v = d - c
            val w = a - b + c - d
            val determinant = u.x * v.y - u.y * v.x
            if (abs(determinant) < 1e-6) return null
            val g = (w.x * v.y - w.y * v.x) / determinant
            val h = (u.x * w.y - u.y * w.x) / determinant
            if (minOf(1 + g, 1 + h, 1 + g + h) <= 0) return null
            return Homography(a, b * (1 + g) - a, d * (1 + h) - a, g, h)
        }
    }
}

/** Searches the guide-relative silhouette and Y seams before refining each of the seven vertices. */
internal object CornerLocator {
    data class Location(
        val quads: List<List<CornerPoint>>,
        val score: Double,
    )

    fun locate(
        frame: YuvFrame,
        guide: CornerGuide,
    ): Location? {
        if (guide.radius < 18) return null

        val center = guide.vertices().last()
        val darkSurroundings = guide.vertices(scale = 1.13).take(6).count { frame.stickerStrength(it) < 28 } >= 5

        fun contrast(
            a: CornerPoint,
            b: CornerPoint,
            seam: Boolean,
        ): Double {
            val edge = b - a
            val normal = CornerPoint(-edge.y, edge.x) * (guide.radius * 0.074 / hypot(edge.x, edge.y))
            var total = 0.0
            for (index in 1..11) {
                val point = a + edge * (index / 12.0)
                val inside = point - normal
                val outside = point + normal
                val step = frame.contrast(inside, outside)
                val rating =
                    when {
                        seam -> maxOf(step, minOf(frame.luma(inside), frame.luma(outside)) - frame.luma(point))
                        darkSurroundings -> minOf(step, frame.stickerStrength(inside) - frame.stickerStrength(outside))
                        else -> step
                    }
                total += rating.coerceIn(0, 90) / 90.0
            }
            return total / 11
        }

        fun exterior(points: List<CornerPoint>): Int =
            (0..5).minOf { index ->
                val start = points[index]
                val edge = points[(index + 1) % 6] - start
                val normal = CornerPoint(-edge.y, edge.x) * (guide.radius * 0.10 / hypot(edge.x, edge.y))
                (1..11).count { frame.stickerStrength(start + edge * (it / 12.0) + normal) < 32 }
            }

        fun outline(points: List<CornerPoint>): List<Double> = (0..5).map { contrast(points[it], points[(it + 1) % 6], false) }

        fun seams(points: List<CornerPoint>): List<Double> = listOf(1, 3, 5).map { contrast(points[it], points[6], true) }

        fun score(points: List<CornerPoint>): Double {
            val outline = outline(points)
            val seams = seams(points)
            return 2 * outline.average() + 2 * outline.min() + seams.average() + seams.min()
        }

        var best = guide.vertices()
        var bestScore = 0.0
        for (scale in 7..10) {
            for (roll in -10..10 step 5) {
                val vertices = guide.vertices(scale / 10.0, roll.toDouble())
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        val shift = CornerPoint(dx * guide.radius * 0.07, dy * guide.radius * 0.07)
                        for ((px, py) in listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                            val candidate =
                                vertices.map {
                                    val relative = it - center
                                    val depth = 1 + 0.06 * (px * relative.x + py * relative.y) / guide.radius
                                    center + relative * (1 / depth) + shift
                                }
                            val rating = score(candidate)
                            if (rating > bestScore) {
                                best = candidate
                                bestScore = rating
                            }
                        }
                    }
                }
            }
        }
        val seed = best
        for (step in listOf(0.07, 0.035, 0.018)) {
            for (index in best.indices) {
                val origin = best[index]
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        val point = origin + CornerPoint(dx * step * guide.radius, dy * step * guide.radius)
                        val drift = point - seed[index]
                        if (hypot(drift.x, drift.y) > guide.radius * 0.15) continue
                        val candidate = best.toMutableList().also { it[index] = point }
                        val rating = score(candidate)
                        if (rating > bestScore) {
                            best = candidate
                            bestScore = rating
                        }
                    }
                }
            }
        }
        if (outline(best).min() < 0.23 || seams(best).min() < 0.19) return null
        if (darkSurroundings && exterior(best) < 8) return null
        return Location(guide.quads(best), bestScore)
    }

    private fun YuvFrame.contrast(
        a: CornerPoint,
        b: CornerPoint,
    ): Int =
        maxOf(
            abs(luma(a) - luma(b)),
            abs(chroma(u, a) - chroma(u, b)),
            abs(chroma(v, a) - chroma(v, b)),
        )

    private fun YuvFrame.stickerStrength(p: CornerPoint): Int = maxOf(luma(p) - 20, abs(chroma(u, p) - 128), abs(chroma(v, p) - 128))

    private fun YuvFrame.luma(p: CornerPoint): Int =
        y.at(p.x.roundToInt().coerceIn(left, right - 1), p.y.roundToInt().coerceIn(top, bottom - 1))

    private fun YuvFrame.chroma(
        plane: YuvPlane,
        p: CornerPoint,
    ): Int = plane.at(p.x.roundToInt().coerceIn(left, right - 1) / 2, p.y.roundToInt().coerceIn(top, bottom - 1) / 2)
}

internal fun unwarp(
    frame: YuvFrame,
    quad: List<CornerPoint>,
    side: Int = 96,
): YuvFrame? {
    require(side > 0 && side % 2 == 0)
    val transform = Homography.from(quad) ?: return null

    fun plane(
        source: YuvPlane,
        subsample: Int,
    ): YuvPlane {
        val width = side / subsample
        val bytes = ByteArray(width * width)
        for (row in 0 until width) {
            for (column in 0 until width) {
                val p = transform.apply((column + 0.5) / width, (row + 0.5) / width)
                val x = (p.x / subsample).coerceIn((frame.left / subsample).toDouble(), ((frame.right - 1) / subsample).toDouble())
                val y = (p.y / subsample).coerceIn((frame.top / subsample).toDouble(), ((frame.bottom - 1) / subsample).toDouble())
                val x0 = x.toInt()
                val y0 = y.toInt()
                val x1 = minOf(x0 + 1, (frame.right - 1) / subsample)
                val y1 = minOf(y0 + 1, (frame.bottom - 1) / subsample)
                val fx = x - x0
                val fy = y - y0
                val top = source.at(x0, y0) * (1 - fx) + source.at(x1, y0) * fx
                val bottom = source.at(x0, y1) * (1 - fx) + source.at(x1, y1) * fx
                bytes[row * width + column] = (top * (1 - fy) + bottom * fy).roundToInt().toByte()
            }
        }
        return YuvPlane(ByteBuffer.wrap(bytes), width, 1)
    }
    return YuvFrame(plane(frame.y, 1), plane(frame.u, 2), plane(frame.v, 2), 0, 0, side, side)
}

internal data class CornerFrame(
    val detected: Boolean,
    val faces: List<FrameStats?>,
    val score: Double = 0.0,
)

internal object CornerReader {
    fun read(
        frame: YuvFrame,
        guide: CornerGuide,
    ): CornerFrame {
        val located = CornerLocator.locate(frame, guide) ?: return CornerFrame(false, List(3) { null })
        val faces =
            located.quads.map { quad ->
                unwarp(frame, quad)?.let {
                    FrameReader.readAt(it, GridSquare(0.0, 0.0, it.width.toDouble()))
                }
            }

        fun hasStickerEvidence(face: FrameStats) = face.cells.count { it != null && FrameQuality.isStickerEvidence(it) } >= 6

        val plausible = faces.filterNotNull().count(::hasStickerEvidence)
        val locatedFaces =
            faces.map { face ->
                face?.takeIf { hasStickerEvidence(it) || FrameQuality.assessShutter(it) != CaptureQuality.ACCEPTABLE }
            }
        return CornerFrame(plausible >= 2, locatedFaces, located.score)
    }
}
