package dev.cubentia.domain.input

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Renderer-local look-at-origin camera angles, in degrees. */
data class ViewOrbit(
    val yawDegrees: Double,
    val pitchDegrees: Double,
) {
    fun eye(): Vector3 {
        val yaw = Math.toRadians(yawDegrees)
        val pitch = Math.toRadians(pitchDegrees)
        val radius = ViewPose.DEFAULT_EYE.length()
        return Vector3(radius * cos(pitch) * sin(yaw), radius * sin(pitch), radius * cos(pitch) * cos(yaw))
    }

    fun rotated(
        yaw: Double,
        pitch: Double,
    ): ViewOrbit {
        val wrapped = wrapYaw(yawDegrees + yaw)
        return ViewOrbit(wrapped, (pitchDegrees + pitch).coerceIn(-PITCH_LIMIT, PITCH_LIMIT))
    }

    fun dragged(
        dx: Double,
        dy: Double,
        sidePx: Double,
    ): ViewOrbit = rotated(-dx * DRAG_DEGREES / sidePx.coerceAtLeast(1.0), dy * DRAG_DEGREES / sidePx.coerceAtLeast(1.0))

    fun isHome(tolerance: Double = HOME_TOLERANCE_DEGREES): Boolean =
        abs(shortestYawDelta(yawDegrees, DEFAULT.yawDegrees)) <= tolerance &&
            abs(DEFAULT.pitchDegrees - pitchDegrees) <= tolerance

    fun towards(
        target: ViewOrbit,
        fraction: Double,
    ): ViewOrbit {
        if (fraction.isNaN()) return this
        val t = fraction.coerceIn(0.0, 1.0)
        if (t >= 1.0) return target
        if (t <= 0.0) return this
        val yaw = wrapYaw(yawDegrees + shortestYawDelta(yawDegrees, target.yawDegrees) * t)
        val pitch = (pitchDegrees + (target.pitchDegrees - pitchDegrees) * t).coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
        return ViewOrbit(yaw, pitch)
    }

    companion object {
        const val PITCH_LIMIT = 85.0
        const val DRAG_DEGREES = 180.0
        const val STEP_DEGREES = 45.0
        const val HOME_TOLERANCE_DEGREES = 1.0
        val DEFAULT =
            ViewOrbit(
                Math.toDegrees(atan2(ViewPose.DEFAULT_EYE.x, ViewPose.DEFAULT_EYE.z)),
                Math.toDegrees(
                    asin(ViewPose.DEFAULT_EYE.y / ViewPose.DEFAULT_EYE.length()),
                ),
            )

        private fun wrapYaw(degrees: Double): Double {
            val wrapped = (degrees % 360.0 + 360.0) % 360.0
            return if (wrapped > 180.0) wrapped - 360.0 else wrapped
        }

        private fun shortestYawDelta(
            from: Double,
            to: Double,
        ): Double = wrapYaw(to - from)
    }
}
