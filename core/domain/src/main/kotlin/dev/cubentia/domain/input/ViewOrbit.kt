package dev.cubentia.domain.input

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
        val wrapped = ((yawDegrees + yaw) % 360 + 360) % 360
        return ViewOrbit(if (wrapped > 180) wrapped - 360 else wrapped, (pitchDegrees + pitch).coerceIn(-PITCH_LIMIT, PITCH_LIMIT))
    }

    fun dragged(
        dx: Double,
        dy: Double,
        sidePx: Double,
    ): ViewOrbit = rotated(-dx * DRAG_DEGREES / sidePx.coerceAtLeast(1.0), dy * DRAG_DEGREES / sidePx.coerceAtLeast(1.0))

    companion object {
        const val PITCH_LIMIT = 85.0
        const val DRAG_DEGREES = 180.0
        const val STEP_DEGREES = 45.0
        val DEFAULT =
            ViewOrbit(
                Math.toDegrees(atan2(ViewPose.DEFAULT_EYE.x, ViewPose.DEFAULT_EYE.z)),
                Math.toDegrees(
                    asin(ViewPose.DEFAULT_EYE.y / ViewPose.DEFAULT_EYE.length()),
                ),
            )
    }
}
