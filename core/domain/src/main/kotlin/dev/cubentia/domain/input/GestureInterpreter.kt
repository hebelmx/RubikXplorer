package dev.cubentia.domain.input

import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.CubeGeometry
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Slice
import dev.cubentia.domain.cube.SliceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.WideTurn
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

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

    const val LOCK_DISTANCE = 0.35
    const val DOMINANCE = 2
    private const val RADIUS = 1.5
    private val axes = listOf(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector3(0.0, 0.0, 1.0))
    private val positiveFaces = listOf(Face.R, Face.U, Face.F)
    private val negativeFaces = listOf(Face.L, Face.D, Face.B)

    fun interpret(
        path: TouchPath,
        pose: ViewPose,
    ): MoveIntent? = release(GestureInput(listOf(path)), pose)

    fun release(
        input: GestureInput,
        pose: ViewPose,
    ): MoveIntent? = (preview(input, pose) as? DragPreview.Active)?.let { MoveIntent(it.move) }

    fun classifyPair(
        a: TouchPath,
        b: TouchPath,
        pose: ViewPose,
    ): PairKind {
        val startA = a.points.firstOrNull() ?: return PairKind.UNDECIDED
        val startB = b.points.firstOrNull() ?: return PairKind.UNDECIDED
        return classifyPair(startA, startB, a.points.last(), b.points.last(), pose)
    }

    private fun classifyPair(
        startA: ScreenPoint,
        startB: ScreenPoint,
        endA: ScreenPoint,
        endB: ScreenPoint,
        pose: ViewPose,
    ): PairKind {
        val ax = endA.x - startA.x
        val ay = endA.y - startA.y
        val bx = endB.x - startB.x
        val by = endB.y - startB.y
        val a = hypot(ax, ay)
        val b = hypot(bx, by)
        val decide = 0.04 * min(pose.width, pose.height)
        if (max(a, b) < decide) return PairKind.UNDECIDED
        if (interpretTwist(startA, startB, endA, endB, pose) != null) return PairKind.TWIST
        return if (min(a, b) >= decide / 2 && min(a, b) >= max(a, b) / 2 &&
            ax * bx + ay * by >= a * b * cos(Math.toRadians(35.0))
        ) {
            PairKind.PARALLEL
        } else {
            PairKind.PINCH
        }
    }

    fun preview(
        input: GestureInput,
        pose: ViewPose,
    ): DragPreview? {
        val paths = input.paths.map { it.points }
        if (paths.any { it.isEmpty() }) return null
        val hits = paths.map { pick(pose.ray(it.first().x, it.first().y)) }
        val driver = hits.indexOfFirst { it != null }
        if (driver < 0) return null
        val (cubie, point, normal) = checkNotNull(hits[driver])
        val path = paths[driver]
        val wide = paths.size == 2
        val plane = cubie.dot(normal) + CubeGeometry.STICKER_LIFT
        val tangents = (0..2).filter { normal[it] == 0.0 }
        var delta = Vector3(0.0, 0.0, 0.0)
        var major = -1
        var pairLocked = false
        for (index in 1..paths.maxOf { it.lastIndex }) {
            val sample = path[min(index, path.lastIndex)]
            val ray = pose.ray(sample.x, sample.y)
            val denominator = ray.direction.dot(normal)
            if (abs(denominator) >= 1e-9) {
                val distance = (plane - ray.origin.dot(normal)) / denominator
                if (distance > 0) delta = ray.origin + ray.direction * distance - point
            }
            if (major < 0 && delta.length() >= LOCK_DISTANCE) {
                val (a, b) = tangents
                major = if (abs(delta[a]) >= abs(delta[b])) a else b
                if (abs(delta[major]) < DOMINANCE * abs(delta[a + b - major])) return null
            }
            if (wide && !pairLocked) {
                val (a, b) = paths
                when (classifyPair(a.first(), b.first(), a[min(index, a.lastIndex)], b[min(index, b.lastIndex)], pose)) {
                    PairKind.TWIST -> return null
                    PairKind.PARALLEL -> pairLocked = major >= 0 && delta.length() >= LOCK_DISTANCE
                    else -> Unit
                }
            }
        }
        if (major < 0 || (wide && !pairLocked)) return null
        val rotation = normal.cross(axes[major])
        val axis = (0..2).first { abs(rotation[it]) > 0.5 }
        val layer =
            if (wide) {
                val indices = hits.mapNotNull { it?.first?.get(axis)?.toInt() }
                val positive = 1 in indices
                val negative = -1 in indices
                if (positive == negative) return null
                if (positive) 1 else -1
            } else {
                cubie[axis].toInt()
            }
        if (delta.length() < LOCK_DISTANCE) return DragPreview.CancelZone
        val travel = delta[major]
        val direction = if (travel >= 0) 1 else -1
        val outward =
            if (layer != 0) {
                layer
            } else if (axis == 2) {
                1
            } else {
                -1
            }
        val turn = if (rotation[axis] * direction * outward > 0) Turn.COUNTERCLOCKWISE else Turn.CLOCKWISE
        val face = if (layer > 0) positiveFaces[axis] else negativeFaces[axis]
        val move =
            when {
                wide -> WideTurn(face, turn)
                layer == 0 -> SliceTurn(Slice.entries[axis], turn)
                else -> FaceTurn(face, turn)
            }
        val degrees = sign(travel) * min(90.0, Math.toDegrees(max(0.0, abs(travel) - LOCK_DISTANCE) / RADIUS))
        return DragPreview.Active(move, degrees)
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
