package dev.rubikxplorer.domain.input

import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Slice
import dev.rubikxplorer.domain.cube.SliceTurn
import dev.rubikxplorer.domain.cube.Turn
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure start-face picking and layer recognition. Uncertain drags produce no intent. */
object GestureInterpreter {
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
        // Test only outward faces facing the camera, then take the nearest bounded hit.
        val hit =
            (0..2)
                .flatMap { axis -> listOf(-1, 1).map { axis to it } }
                .mapNotNull { (axis, sign) ->
                    val normal = axes[axis] * sign.toDouble()
                    val denominator = ray.direction.dot(normal)
                    if (denominator >= -1e-9) return@mapNotNull null
                    val distance = (1.5 - ray.origin.dot(normal)) / denominator
                    val point = ray.origin + ray.direction * distance
                    if (distance <= 0 || (0..2).any { abs(point[it]) > 1.5 + 1e-9 }) {
                        null
                    } else {
                        Triple(distance, point, normal)
                    }
                }.minByOrNull { it.first } ?: return null
        val (_, point, normal) = hit
        val end = path.points.last()
        val lastRay = pose.ray(end.x, end.y)
        val denominator = lastRay.direction.dot(normal)
        if (abs(denominator) < 1e-9) return null
        val distance = (1.5 - lastRay.origin.dot(normal)) / denominator
        if (distance <= 0) return null
        val delta = lastRay.origin + lastRay.direction * distance - point
        val tangents = (0..2).filter { normal[it] == 0.0 }.sortedByDescending { abs(delta[it]) }
        val major = tangents[0]
        if (delta.length() < MIN_DRAG || abs(delta[major]) < DOMINANCE * abs(delta[tangents[1]])) return null
        val rotation = normal.cross(axes[major] * if (delta[major] > 0) 1.0 else -1.0)
        val axis = (0..2).first { abs(rotation[it]) > 0.5 }
        val layer = point[axis].roundToInt().coerceIn(-1, 1)
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
}
