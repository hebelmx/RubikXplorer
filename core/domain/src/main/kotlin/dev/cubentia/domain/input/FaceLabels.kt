package dev.cubentia.domain.input

import dev.cubentia.domain.cube.CubeGeometry
import dev.cubentia.domain.cube.Face
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

data class FaceLabel(
    val face: Face,
    val left: Double,
    val top: Double,
)

/**
 * Pure 2D placement of face letters (U, D, F, B, R, L) beside faces that clearly face the camera.
 * Letters sit outside the cube's projected silhouette and inside the stage without overlapping any
 * sticker quad or another placed label.
 */
object FaceLabels {
    const val MIN_FACING_DEGREES = 20.0
    private val MIN_FACING_SIN = sin(Math.toRadians(MIN_FACING_DEGREES))
    private val EXTENT = 1.0 + max(CubeGeometry.BODY_HALF.toDouble(), CubeGeometry.STICKER_LIFT.toDouble())
    private val RETRY_ANGLES_DEGREES = listOf(0.0, 15.0, -15.0, 30.0, -30.0, 45.0, -45.0, 60.0, -60.0, 75.0, -75.0, 90.0, -90.0)
    private const val STEP_PX = 2.0

    fun place(
        pose: ViewPose,
        labelWidth: Double,
        labelHeight: Double,
        gap: Double,
    ): List<FaceLabel> {
        val stageW = pose.width.toDouble()
        val stageH = pose.height.toDouble()
        if (labelWidth <= 0.0 || labelHeight <= 0.0 || labelWidth > stageW || labelHeight > stageH) return emptyList()
        val hull = silhouette(pose)
        if (hull.size < 3) return emptyList()
        val origin = pose.project(Vector3(0.0, 0.0, 0.0))
        val maxWalk = hypot(stageW, stageH)
        val candidates =
            Face.entries
                .mapNotNull { face ->
                    val normal = normalOf(face)
                    val centre = normal * 1.5
                    val toEye = pose.eye - centre
                    val len = toEye.length()
                    val facingSin = if (len > 0.0) normal.dot(toEye * (1.0 / len)) else 0.0
                    if (facingSin <= MIN_FACING_SIN) null else Triple(face, centre, facingSin)
                }.sortedByDescending { it.third }

        val placed = mutableListOf<FaceLabel>()
        for ((face, centre, _) in candidates) {
            val projected = pose.project(centre)
            val dx = projected.x - origin.x
            val dy = projected.y - origin.y
            val len = hypot(dx, dy)
            val baseDir = if (len < 1.0) ScreenPoint(0.0, 1.0) else ScreenPoint(dx / len, dy / len)
            var found: FaceLabel? = null
            for (angle in RETRY_ANGLES_DEGREES) {
                val dir = rotate(baseDir, angle)
                var dist = 0.0
                while (dist <= maxWalk) {
                    val cx = projected.x + dir.x * dist
                    val cy = projected.y + dir.y * dist
                    val rawLeft = cx - labelWidth / 2.0
                    val rawTop = cy - labelHeight / 2.0
                    if (!boxIntersectsPolygon(rawLeft - gap, rawTop - gap, rawLeft + labelWidth + gap, rawTop + labelHeight + gap, hull)) {
                        val clampedLeft = rawLeft.coerceIn(0.0, stageW - labelWidth)
                        val clampedTop = rawTop.coerceIn(0.0, stageH - labelHeight)
                        val overlapsHull =
                            boxIntersectsPolygon(
                                clampedLeft - gap,
                                clampedTop - gap,
                                clampedLeft + labelWidth + gap,
                                clampedTop + labelHeight + gap,
                                hull,
                            )
                        val overlapsPlaced =
                            placed.any { other ->
                                boxesOverlap(
                                    clampedLeft - gap,
                                    clampedTop - gap,
                                    clampedLeft + labelWidth + gap,
                                    clampedTop + labelHeight + gap,
                                    other.left,
                                    other.top,
                                    other.left + labelWidth,
                                    other.top + labelHeight,
                                )
                            }
                        if (!overlapsHull && !overlapsPlaced) {
                            found = FaceLabel(face, clampedLeft, clampedTop)
                            break
                        }
                        if (rawLeft != clampedLeft && rawTop != clampedTop) break
                    }
                    dist += STEP_PX
                }
                if (found != null) break
            }
            if (found != null) placed += found
        }
        return placed
    }

    /** Convex hull of the 8 projected cube corners at ±(1 + max(BODY_HALF, STICKER_LIFT)). */
    fun silhouette(pose: ViewPose): List<ScreenPoint> {
        val corners = ArrayList<ScreenPoint>(8)
        for (sx in intArrayOf(-1, 1)) {
            for (sy in intArrayOf(-1, 1)) {
                for (sz in intArrayOf(-1, 1)) {
                    corners += pose.project(Vector3(sx * EXTENT, sy * EXTENT, sz * EXTENT))
                }
            }
        }
        return convexHull(corners)
    }

    /** Projected 4-corner quads of all stickers on faces oriented toward the camera. */
    fun stickerQuads(pose: ViewPose): List<List<ScreenPoint>> {
        val lift = 1.0 + CubeGeometry.STICKER_LIFT.toDouble()
        val half = CubeGeometry.STICKER_HALF.toDouble()
        val quads = mutableListOf<List<ScreenPoint>>()
        for (face in Face.entries) {
            val normal = normalOf(face)
            val faceCentre = normal * lift
            if ((pose.eye - faceCentre).dot(normal) <= 0.0) continue
            val (u, v) = tangentsOf(normal)
            for (row in -1..1) {
                for (col in -1..1) {
                    val cubieSticker = faceCentre + u * col.toDouble() + v * row.toDouble()
                    quads +=
                        listOf(
                            pose.project(cubieSticker - u * half - v * half),
                            pose.project(cubieSticker + u * half - v * half),
                            pose.project(cubieSticker + u * half + v * half),
                            pose.project(cubieSticker - u * half + v * half),
                        )
                }
            }
        }
        return quads
    }

    private fun normalOf(face: Face): Vector3 =
        when (face) {
            Face.R -> Vector3(1.0, 0.0, 0.0)
            Face.L -> Vector3(-1.0, 0.0, 0.0)
            Face.U -> Vector3(0.0, 1.0, 0.0)
            Face.D -> Vector3(0.0, -1.0, 0.0)
            Face.F -> Vector3(0.0, 0.0, 1.0)
            Face.B -> Vector3(0.0, 0.0, -1.0)
        }

    private fun tangentsOf(normal: Vector3): Pair<Vector3, Vector3> {
        val u = if (normal.x != 0.0) Vector3(0.0, 0.0, -normal.x) else Vector3(1.0, 0.0, 0.0)
        val v = normal.cross(u)
        return u to v
    }

    private fun rotate(
        dir: ScreenPoint,
        degrees: Double,
    ): ScreenPoint {
        if (degrees == 0.0) return dir
        val rad = Math.toRadians(degrees)
        val c = cos(rad)
        val s = sin(rad)
        return ScreenPoint(dir.x * c - dir.y * s, dir.x * s + dir.y * c)
    }

    private fun convexHull(points: List<ScreenPoint>): List<ScreenPoint> {
        val sorted = points.sortedWith(compareBy<ScreenPoint> { it.x }.thenBy { it.y })
        if (sorted.size <= 1) return sorted
        val lower = ArrayList<ScreenPoint>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0.0) {
                lower.removeAt(lower.lastIndex)
            }
            lower += p
        }
        val upper = ArrayList<ScreenPoint>()
        for (i in sorted.indices.reversed()) {
            val p = sorted[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0.0) {
                upper.removeAt(upper.lastIndex)
            }
            upper += p
        }
        lower.removeAt(lower.lastIndex)
        upper.removeAt(upper.lastIndex)
        return lower + upper
    }

    private fun cross(
        o: ScreenPoint,
        a: ScreenPoint,
        b: ScreenPoint,
    ): Double = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    private fun boxesOverlap(
        leftA: Double,
        topA: Double,
        rightA: Double,
        bottomA: Double,
        leftB: Double,
        topB: Double,
        rightB: Double,
        bottomB: Double,
    ): Boolean = leftA <= rightB && rightA >= leftB && topA <= bottomB && bottomA >= topB

    /** Separating Axis Theorem intersection check between an AABB and a convex polygon. */
    internal fun boxIntersectsPolygon(
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        poly: List<ScreenPoint>,
    ): Boolean {
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (p in poly) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        if (right < minX || left > maxX || bottom < minY || top > maxY) return false
        val box =
            listOf(
                ScreenPoint(left, top),
                ScreenPoint(right, top),
                ScreenPoint(right, bottom),
                ScreenPoint(left, bottom),
            )
        for (i in box.indices) {
            val a = box[i]
            val b = box[(i + 1) % box.size]
            if (separates(a, b, poly, box)) return false
        }
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            if (separates(a, b, poly, box)) return false
        }
        return true
    }

    private fun separates(
        a: ScreenPoint,
        b: ScreenPoint,
        poly: List<ScreenPoint>,
        box: List<ScreenPoint>,
    ): Boolean {
        val nx = -(b.y - a.y)
        val ny = b.x - a.x
        if (nx == 0.0 && ny == 0.0) return false
        var pMin = Double.POSITIVE_INFINITY
        var pMax = Double.NEGATIVE_INFINITY
        for (p in poly) {
            val proj = p.x * nx + p.y * ny
            if (proj < pMin) pMin = proj
            if (proj > pMax) pMax = proj
        }
        var bMin = Double.POSITIVE_INFINITY
        var bMax = Double.NEGATIVE_INFINITY
        for (c in box) {
            val proj = c.x * nx + c.y * ny
            if (proj < bMin) bMin = proj
            if (proj > bMax) bMax = proj
        }
        return bMax < pMin || pMax < bMin
    }
}
