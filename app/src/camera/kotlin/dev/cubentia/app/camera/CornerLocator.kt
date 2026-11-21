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
    val rotationDegrees: Int = 0,
) {
    private val center = CornerPoint(square.left + square.side / 2, square.top + square.side / 2)
    val radius = square.side / 2

    fun vertices(
        scale: Double = 1.0,
        roll: Double = 0.0,
    ): List<CornerPoint> =
        List(6) { index ->
            val angle = (-90 - index * 60 - rotationDegrees + roll) * PI / 180
            center + CornerPoint(cos(angle), sin(angle)) * (radius * scale)
        } + center

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

/**
 * Plug-in seam for Quick Scan 3D corner detection: locates the three visible face quads (`[U/D, F/L, R/B]`) in frame
 * pixels. Allows the pure-Kotlin [CornerLocator] and the upcoming LiteRT 7-keypoint detector to share [CornerReader],
 * [unwarp], geometric validation, and JVM test coverage.
 */
internal fun interface CornerDetector {
    fun locate(
        frame: YuvFrame,
        guide: CornerGuide,
    ): CornerLocator.Location?
}

/** Searches the guide-relative silhouette and Y seams before refining each of the seven vertices. */
internal object CornerLocator : CornerDetector {
    data class Location(
        val quads: List<List<CornerPoint>>,
        val score: Double,
    )

    /**
     * Reusable geometric sanity guard for any [CornerDetector] (heuristic or LiteRT 7-keypoint output): ensures the
     * nearest corner `C` stays inside the framing zone and all 3 face quads have non-collapsed, roughly parallel sides.
     */
    internal fun isValidCubeGeometry(
        points: List<CornerPoint>,
        guide: CornerGuide,
    ): Boolean {
        if (points.size != 7) return false
        val center = guide.vertices().last()
        // 1. Center vertex C must stay within a reasonable norm distance of the guide center.
        val c = points[6]
        if (hypot(c.x - center.x, c.y - center.y) > guide.radius * 0.55) return false
        // 2. Each of the 3 face quads must have roughly parallel/converging opposite sides and non-collapsed edges.
        val minEdge = guide.radius * 0.24
        return guide.quads(points).all { (a, b, d, e) ->
            val top = b - a
            val bottom = d - e
            val left = e - a
            val right = d - b
            val topLen = hypot(top.x, top.y)
            val botLen = hypot(bottom.x, bottom.y)
            val leftLen = hypot(left.x, left.y)
            val rightLen = hypot(right.x, right.y)
            if (minOf(topLen, botLen, leftLen, rightLen) < minEdge) return@all false
            val cosH = (top.x * bottom.x + top.y * bottom.y) / (topLen * botLen)
            val cosV = (left.x * right.x + left.y * right.y) / (leftLen * rightLen)
            // Opposite edges within ~35 degrees (cos >= 0.82) and comparable length.
            cosH >= 0.82 && cosV >= 0.82 &&
                (topLen / botLen) in 0.50..2.00 && (leftLen / rightLen) in 0.50..2.00
        }
    }

    override fun locate(
        frame: YuvFrame,
        guide: CornerGuide,
    ): Location? {
        if (guide.radius < 18) return null

        val rotation = guide.rotationDegrees
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

        fun exteriorChromaPenalty(points: List<CornerPoint>): Double {
            var colored = 0
            var samples = 0
            for (index in 0..5) {
                val start = points[index]
                val edge = points[(index + 1) % 6] - start
                val len = hypot(edge.x, edge.y)
                if (len < 1.0) continue
                // Vertices are CCW in image coordinates (y-down), so (-edge.y, edge.x) points outward.
                val normal = CornerPoint(-edge.y, edge.x) * (guide.radius * 0.12 / len)
                for (step in listOf(0.25, 0.5, 0.75)) {
                    val p = start + edge * step + normal
                    samples++
                    val sat = maxOf(abs(frame.chroma(frame.u, p) - 128), abs(frame.chroma(frame.v, p) - 128))
                    if (sat > 38) colored++
                }
            }
            return if (samples == 0) 0.0 else 1.2 * colored / samples
        }

        fun outline(points: List<CornerPoint>): List<Double> = (0..5).map { contrast(points[it], points[(it + 1) % 6], false) }

        fun seams(points: List<CornerPoint>): List<Double> = listOf(1, 3, 5).map { contrast(points[it], points[6], true) }

        fun score(
            points: List<CornerPoint>,
            checkExterior: Boolean = true,
        ): Double {
            val outline = outline(points)
            val seams = seams(points)
            val penalty = if (checkExterior) exteriorChromaPenalty(points) else 0.0
            return 2 * outline.average() + 2 * outline.min() + seams.average() + seams.min() - penalty
        }

        fun polishVertices(
            initial: List<CornerPoint>,
            minSeam: Double = 0.19,
            maxDrift: Double = 0.09,
        ): Location? {
            var polished = initial
            var polishedScore = score(polished, checkExterior = true)
            for (step in listOf(0.05, 0.025, 0.012)) {
                for (index in polished.indices) {
                    val origin = polished[index]
                    for (dx in -1..1) {
                        for (dy in -1..1) {
                            val point = origin + CornerPoint(dx * step * guide.radius, dy * step * guide.radius)
                            val drift = point - initial[index]
                            if (hypot(drift.x, drift.y) > guide.radius * maxDrift) continue
                            val candidate = polished.toMutableList().also { it[index] = point }
                            val rating = score(candidate, checkExterior = true)
                            if (rating > polishedScore) {
                                polished = candidate
                                polishedScore = rating
                            }
                        }
                    }
                }
            }
            if (!isValidCubeGeometry(polished, guide)) return null
            if (outline(polished).min() < 0.23 || seams(polished).min() < minSeam) return null
            if (darkSurroundings && exterior(polished) < 6) return null
            return Location(guide.quads(polished), polishedScore)
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
        if (outline(best).min() >= 0.45 && seams(best).min() >= 0.35) {
            polishVertices(best)?.let { return it }
        }

        // Real-world / perspective / off-center frames: use bottom-up sticker-blob detection first, then sub-pixel polish.
        StickerBlobDetector.detect(frame, guide)?.let { blobLoc ->
            val q0 = blobLoc.quads[0]
            val q1 = blobLoc.quads[1]
            val q2 = blobLoc.quads[2]
            val rawPts = listOf(q0[0], q0[3], q1[3], q1[2], q2[2], q0[1], q0[2])
            val anchor = rawPts[6]
            var snapped = rawPts
            var snappedScore = score(rawPts, checkExterior = true)
            val snapStep = guide.radius * 0.04
            for (scale in listOf(0.95, 1.0, 1.05)) {
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        val shift = CornerPoint(dx * snapStep, dy * snapStep)
                        val candidate = rawPts.map { anchor + (it - anchor) * scale + shift }
                        val rating = score(candidate, checkExterior = true)
                        if (rating > snappedScore) {
                            snapped = candidate
                            snappedScore = rating
                        }
                    }
                }
            }
            polishVertices(snapped)?.let { return it }
        }

        // Fallback perspective search if blob segmentation found too few uniform regions.
        if (outline(best).min() < 0.45 || seams(best).min() < 0.35) {
            val zoneStep = guide.radius * 0.11
            var perspBest = best
            var perspBestScore = bestScore
            var bestScale = 0.80
            var bestShift = CornerPoint(0.0, 0.0)
            var bestRoll = 0.0
            for (scale in listOf(0.58, 0.72, 0.86, 1.00)) {
                for (roll in listOf(-10.0, 0.0, 10.0)) {
                    for (persp in listOf(0.16, 0.30)) {
                        for (pitch in listOf(24.0, 35.26)) {
                            val base = projectCube(center, guide.radius * scale, rotation, pitch, 0.0, roll, persp)
                            for (dx in -2..2) {
                                for (dy in -2..2) {
                                    val shift = CornerPoint(dx * zoneStep, dy * zoneStep)
                                    val candidate = base.map { it + shift }
                                    val rating = score(candidate)
                                    if (rating > perspBestScore) {
                                        perspBest = candidate
                                        perspBestScore = rating
                                        bestScale = scale
                                        bestShift = shift
                                        bestRoll = roll
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val fineStep = guide.radius * 0.05
            for (scaleDelta in listOf(-0.04, 0.0, 0.04)) {
                for (pitch in listOf(22.0, 30.0, 42.0)) {
                    for (yaw in listOf(-10.0, 0.0, 10.0)) {
                        for (persp in listOf(0.14, 0.28, 0.36)) {
                            val base =
                                projectCube(center, guide.radius * (bestScale + scaleDelta), rotation, pitch, yaw, bestRoll, persp)
                            for (dx in -1..1) {
                                for (dy in -1..1) {
                                    val shift = bestShift + CornerPoint(dx * fineStep, dy * fineStep)
                                    val candidate = base.map { it + shift }
                                    val rating = score(candidate)
                                    if (rating > perspBestScore) {
                                        perspBest = candidate
                                        perspBestScore = rating
                                    }
                                }
                            }
                        }
                    }
                }
            }
            polishVertices(perspBest, maxDrift = 0.15)?.let { return it }
        }
        return null
    }

    /**
     * Projects the 7 visible corners `[T, UL, LL, B, LR, UR, C]` of a unit cube `[-1, 1]^3` through a pinhole
     * perspective camera (with [persp] = `0.0` matching orthographic) rotated into frame pixels by [frameRotation].
     */
    internal fun projectCube(
        center: CornerPoint,
        radius: Double,
        frameRotation: Int,
        pitchDeg: Double,
        yawDeg: Double,
        rollDeg: Double,
        persp: Double,
    ): List<CornerPoint> {
        val yaw = (45.0 + yawDeg) * PI / 180.0
        val pitch = pitchDeg * PI / 180.0
        val roll = (-rollDeg - frameRotation) * PI / 180.0
        val cosY = cos(yaw)
        val sinY = sin(yaw)
        val cosP = cos(pitch)
        val sinP = sin(pitch)
        val cosR = cos(roll)
        val sinR = sin(roll)
        // Scale so that at orthographic pitch=35.26°, yaw=0, the outer hexagon circumradius equals `radius` (sqrt(8/3)).
        val unitScale = radius / 1.632993161855452
        val corners3D =
            listOf(
                Triple(-1.0, 1.0, -1.0), // T
                Triple(-1.0, 1.0, 1.0), // UL
                Triple(-1.0, -1.0, 1.0), // LL
                Triple(1.0, -1.0, 1.0), // B
                Triple(1.0, -1.0, -1.0), // LR
                Triple(1.0, 1.0, -1.0), // UR
                Triple(1.0, 1.0, 1.0), // C (nearest corner)
            )
        return corners3D.map { (cx, cy, cz) ->
            // Yaw around Y
            val x1 = cx * cosY - cz * sinY
            val z1 = cx * sinY + cz * cosY
            // Pitch around X (tilts top face toward viewer)
            val y2 = cy * cosP - z1 * sinP
            val z2 = cy * sinP + z1 * cosP
            // Perspective foreshortening: z2 > 0 is closer to camera
            val depth = (1.0 - z2 * persp / 1.732).coerceAtLeast(0.35)
            val px = x1 / depth
            val py = -y2 / depth
            // Roll in image plane (down is +y)
            val rx = px * cosR - py * sinR
            val ry = px * sinR + py * cosR
            center + CornerPoint(rx * unitScale, ry * unitScale)
        }
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
    val normalizedQuads: List<List<CornerPoint>> = emptyList(),
    val frameQuads: List<List<CornerPoint>> = emptyList(),
)

internal object CornerReader {
    /** Tighter cell ROI for oblique 3D unwarped faces to avoid bevel/border desaturation on Red/Orange side faces. */
    internal const val CORNER_CELL_ROI_FRACTION = 0.48

    fun read(
        frame: YuvFrame,
        guide: CornerGuide,
        detector: CornerDetector = CornerLocator,
    ): CornerFrame {
        val located = detector.locate(frame, guide) ?: return CornerFrame(false, List(3) { null })
        val faces =
            located.quads.map { quad ->
                unwarp(frame, quad)?.let {
                    FrameReader.readAt(it, GridSquare(0.0, 0.0, it.width.toDouble()), roiFraction = CORNER_CELL_ROI_FRACTION)
                }
            }

        fun hasStickerEvidence(face: FrameStats) = face.cells.count { it != null && FrameQuality.isStickerEvidence(it) } >= 6

        val plausible = faces.filterNotNull().count(::hasStickerEvidence)
        val locatedFaces =
            faces.map { face ->
                face?.takeIf { hasStickerEvidence(it) || FrameQuality.assessShutter(it) != CaptureQuality.ACCEPTABLE }
            }
        val detected = plausible >= 2
        val normalized =
            if (detected && frame.width > 0 && frame.height > 0) {
                located.quads.map { quad ->
                    quad.map { p ->
                        CornerPoint(
                            (p.x - frame.left) / frame.width.toDouble(),
                            (p.y - frame.top) / frame.height.toDouble(),
                        )
                    }
                }
            } else {
                emptyList()
            }
        val frameQuads = if (detected) located.quads else emptyList()
        return CornerFrame(detected, locatedFaces, located.score, normalized, frameQuads)
    }
}

/**
 * Exponential moving average filter for normalized corner overlay quads so minor frame-to-frame vertex jitter stays
 * calm while large pose changes or losses snap immediately.
 */
internal class CornerOverlaySmoother(
    private val alpha: Double = DEFAULT_ALPHA,
    private val snapThreshold: Double = DEFAULT_SNAP_THRESHOLD,
) {
    private var previous: List<List<CornerPoint>> = emptyList()

    fun update(quads: List<List<CornerPoint>>): List<List<CornerPoint>> {
        if (quads.isEmpty()) {
            previous = emptyList()
            return emptyList()
        }
        val prior = previous
        val next =
            if (prior.size != quads.size || prior.zip(quads).any { (a, b) -> a.size != b.size }) {
                quads
            } else {
                var maxDelta = 0.0
                for (q in quads.indices) {
                    for (p in quads[q].indices) {
                        val d = hypot(quads[q][p].x - prior[q][p].x, quads[q][p].y - prior[q][p].y)
                        if (d > maxDelta) maxDelta = d
                    }
                }
                if (maxDelta > snapThreshold) {
                    quads
                } else {
                    prior.zip(quads) { pq, nq ->
                        pq.zip(nq) { pp, np ->
                            CornerPoint(
                                pp.x + alpha * (np.x - pp.x),
                                pp.y + alpha * (np.y - pp.y),
                            )
                        }
                    }
                }
            }
        previous = next
        return next
    }

    fun reset() {
        previous = emptyList()
    }

    companion object {
        const val DEFAULT_ALPHA = 0.45
        const val DEFAULT_SNAP_THRESHOLD = 0.12
    }
}
