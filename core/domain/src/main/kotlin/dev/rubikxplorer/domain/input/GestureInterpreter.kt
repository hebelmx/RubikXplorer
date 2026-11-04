package dev.rubikxplorer.domain.input

import dev.rubikxplorer.domain.cube.Axis
import dev.rubikxplorer.domain.cube.CubeGeometry
import dev.rubikxplorer.domain.cube.CubeRotation
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Slice
import dev.rubikxplorer.domain.cube.SliceTurn
import dev.rubikxplorer.domain.cube.Turn
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/** Pure start-sticker picking and layer recognition. Uncertain drags produce no intent. */
object GestureInterpreter {
    const val TWIST_MIN_DEGREES = 30.0

    fun startsOnCube(
        point: ScreenPoint,
        pose: ViewPose,
    ): Boolean = boxEntry(pose.ray(point.x, point.y), Vector3(0.0, 0.0, 0.0), 1.5) != null

    fun interpretTwist(
        startA: ScreenPoint,
        startB: ScreenPoint,
        endA: ScreenPoint,
        endB: ScreenPoint,
        pose: ViewPose,
    ): MoveIntent? {
        if (!startsOnCube(ScreenPoint((startA.x + startB.x) / 2, (startA.y + startB.y) / 2), pose)) return null
        if (startA == startB || endA == endB) return null
        val angle = Math.toDegrees(atan2(endB.y - endA.y, endB.x - endA.x) - atan2(startB.y - startA.y, startB.x - startA.x))
        val positive = ((angle % 360) + 360) % 360
        val wrapped = if (positive > 180) positive - 360 else positive
        if (abs(wrapped) < TWIST_MIN_DEGREES) return null
        val clockwise = (wrapped > 0) == (pose.eye.z >= 0)
        return MoveIntent(CubeRotation(Axis.Z, if (clockwise) Turn.CLOCKWISE else Turn.COUNTERCLOCKWISE))
    }

    const val MIN_DRAG = 0.35
    const val DOMINANCE = 2
    private val axes = listOf(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector3(0.0, 0.0, 1.0))

    fun interpret(
        path: TouchPath,
        pose: ViewPose,
    ): MoveIntent? {
        if (path.points.size < 2) return null
        val start = path.points.first()
        val ray = pose.ray(start.x, start.y)
        val hit = pick(ray) ?: return null
        val (cubie, point, normal) = hit
        val plane = cubie.dot(normal) + CubeGeometry.STICKER_LIFT
        val end = path.points.last()
        val lastRay = pose.ray(end.x, end.y)
        val denominator = lastRay.direction.dot(normal)
        if (abs(denominator) < 1e-9) return null
        val distance = (plane - lastRay.origin.dot(normal)) / denominator
        if (distance <= 0) return null
        val delta = lastRay.origin + lastRay.direction * distance - point
        val tangents = (0..2).filter { normal[it] == 0.0 }.sortedByDescending { abs(delta[it]) }
        val major = tangents[0]
        if (delta.length() < MIN_DRAG || abs(delta[major]) < DOMINANCE * abs(delta[tangents[1]])) return null
        val rotation = normal.cross(axes[major] * if (delta[major] > 0) 1.0 else -1.0)
        val axis = (0..2).first { abs(rotation[it]) > 0.5 }
        val layer = cubie[axis].toInt()
        val outward =
            if (layer == 0) {
                if (axis == 2) 1 else -1
            } else {
                layer
            }
        val turn = if (rotation[axis] * outward > 0) Turn.COUNTERCLOCKWISE else Turn.CLOCKWISE
        val move =
            if (layer == 0) {
                SliceTurn(Slice.entries[axis], turn)
            } else {
                FaceTurn(if (layer > 0) listOf(Face.R, Face.U, Face.F)[axis] else listOf(Face.L, Face.D, Face.B)[axis], turn)
            }
        return MoveIntent(move)
    }

    /**
     * The sticker the [ray] first meets on the rendered cube (cubie centre, hit point, outward
     * normal), or null when a cubie body is hit first (a gap between stickers or a grazing body edge)
     * or nothing is hit. Uses the renderer's [CubeGeometry] at the committed (unturned) layout.
     */
    private fun pick(ray: Ray): Triple<Vector3, Vector3, Vector3>? {
        val body = CubeGeometry.BODY_HALF.toDouble()
        val half = CubeGeometry.STICKER_HALF.toDouble()
        val lift = CubeGeometry.STICKER_LIFT.toDouble()
        var nearest = Double.POSITIVE_INFINITY
        var best: Triple<Vector3, Vector3, Vector3>? = null
        for (x in -1..1) {
            for (y in -1..1) {
                for (z in -1..1) {
                    if (x == 0 && y == 0 && z == 0) continue
                    val cubie = Vector3(x.toDouble(), y.toDouble(), z.toDouble())
                    val entry = boxEntry(ray, cubie, body)
                    if (entry != null && entry < nearest) {
                        nearest = entry
                        best = null
                    }
                    for (axis in 0..2) {
                        if (cubie[axis] == 0.0) continue
                        val normal = axes[axis] * cubie[axis]
                        val denominator = ray.direction.dot(normal)
                        if (denominator >= -1e-9) continue
                        val distance = (cubie.dot(normal) + lift - ray.origin.dot(normal)) / denominator
                        if (distance <= 0 || distance >= nearest) continue
                        val point = ray.origin + ray.direction * distance
                        if ((0..2).any { it != axis && abs(point[it] - cubie[it]) > half }) continue
                        nearest = distance
                        best = Triple(cubie, point, normal)
                    }
                }
            }
        }
        return best
    }

    /** Entry distance of [ray] into the axis-aligned box of [half] size around [centre], if hit ahead. */
    private fun boxEntry(
        ray: Ray,
        centre: Vector3,
        half: Double,
    ): Double? {
        var near = 0.0
        var far = Double.POSITIVE_INFINITY
        for (axis in 0..2) {
            val origin = ray.origin[axis] - centre[axis]
            val direction = ray.direction[axis]
            if (abs(direction) < 1e-12) {
                if (abs(origin) > half) return null
                continue
            }
            val a = (-half - origin) / direction
            val b = (half - origin) / direction
            near = max(near, min(a, b))
            far = min(far, max(a, b))
            if (near > far) return null
        }
        return near
    }
}
