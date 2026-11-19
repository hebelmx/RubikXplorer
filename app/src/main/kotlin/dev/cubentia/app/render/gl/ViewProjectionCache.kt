package dev.cubentia.app.render.gl

import dev.cubentia.domain.input.Vector3
import dev.cubentia.domain.input.ViewPose

/** The GL view-projection, rebuilt from the same [ViewPose] picking uses whenever the eye, FOV or surface size changes. */
internal class ViewProjectionCache {
    private var width = 1
    private var height = 1
    private var builtEye: Vector3? = null
    private var builtFovDegrees = 38.0
    private var matrix = FloatArray(16)

    fun resize(
        width: Int,
        height: Int,
    ) {
        this.width = width
        this.height = height
        builtEye = null
    }

    fun matrixFor(
        eye: Vector3,
        fovDegrees: Double = 38.0,
    ): FloatArray {
        if (builtEye != eye || builtFovDegrees != fovDegrees) {
            matrix = ViewPose(width, height, eye, fovDegrees).viewProjection()
            builtEye = eye
            builtFovDegrees = fovDegrees
        }
        return matrix
    }
}
