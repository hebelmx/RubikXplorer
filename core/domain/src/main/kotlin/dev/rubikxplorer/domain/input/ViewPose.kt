package dev.rubikxplorer.domain.input

import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/** World-space value in the viewer frame (+x R, +y U, +z F). */
data class Vector3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    operator fun plus(b: Vector3) = Vector3(x + b.x, y + b.y, z + b.z)

    operator fun minus(b: Vector3) = Vector3(x - b.x, y - b.y, z - b.z)

    operator fun times(s: Double) = Vector3(x * s, y * s, z * s)

    fun dot(b: Vector3) = x * b.x + y * b.y + z * b.z

    fun cross(b: Vector3) = Vector3(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x)

    fun length() = sqrt(dot(this))

    fun normalized() = this * (1.0 / length())

    operator fun get(axis: Int): Double =
        when (axis) {
            0 -> x
            1 -> y
            else -> z
        }
}

data class ScreenPoint(
    val x: Double,
    val y: Double,
)

data class Ray(
    val origin: Vector3,
    val direction: Vector3,
)

/** Fixed look-at-origin camera; FOV spans the narrower viewport side, in degrees. */
data class ViewPose(
    val width: Int,
    val height: Int,
    val eye: Vector3 = DEFAULT_EYE,
    val fovDegrees: Double = 38.0,
) {
    private val forward = (Vector3(0.0, 0.0, 0.0) - eye).normalized()
    private val right = forward.cross(Vector3(0.0, 1.0, 0.0)).normalized()
    private val up = right.cross(forward)
    private val aspect = width.coerceAtLeast(1).toDouble() / height.coerceAtLeast(1)
    private val tanY = tan(Math.toRadians(fovDegrees / 2)) / min(aspect, 1.0)

    fun ray(
        x: Double,
        y: Double,
    ): Ray {
        val nx = 2 * x / width.coerceAtLeast(1) - 1
        val ny = 1 - 2 * y / height.coerceAtLeast(1)
        return Ray(eye, (forward + right * (nx * aspect * tanY) + up * (ny * tanY)).normalized())
    }

    fun project(point: Vector3): ScreenPoint {
        val relative = point - eye
        val depth = relative.dot(forward)
        return ScreenPoint(
            (point.dot(right) / (depth * aspect * tanY) + 1) * width / 2,
            (1 - point.dot(up) / (depth * tanY)) * height / 2,
        )
    }

    /** OpenGL column-major perspective × look-at matrix, with near 1 and far 30. */
    fun viewProjection(): FloatArray {
        val view =
            doubleArrayOf(
                right.x,
                up.x,
                -forward.x,
                0.0,
                right.y,
                up.y,
                -forward.y,
                0.0,
                right.z,
                up.z,
                -forward.z,
                0.0,
                -right.dot(eye),
                -up.dot(eye),
                forward.dot(eye),
                1.0,
            )
        val projection =
            doubleArrayOf(
                1 / (aspect * tanY),
                0.0,
                0.0,
                0.0,
                0.0,
                1 / tanY,
                0.0,
                0.0,
                0.0,
                0.0,
                -31.0 / 29,
                -1.0,
                0.0,
                0.0,
                -60.0 / 29,
                0.0,
            )
        return FloatArray(16) { i -> (0..3).sumOf { k -> projection[(i % 4) + k * 4] * view[k + (i / 4) * 4] }.toFloat() }
    }

    companion object {
        val DEFAULT_EYE = Vector3(4.2, 4.6, 6.4)
    }
}
