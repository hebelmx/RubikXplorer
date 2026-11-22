package dev.cubentia.app.camera.corner

import dev.cubentia.app.camera.CornerGuide
import dev.cubentia.app.camera.CornerLocator
import dev.cubentia.app.camera.CornerPoint
import dev.cubentia.app.camera.YuvFrame
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Pure JVM-testable coordinate and tensor conversion between a camera [YuvFrame] (at any sensor rotation 0°/90°/180°/270°)
 * and the upright `128×128×3` `float32` RGB crop expected by `corner_keypoints.tflite`.
 */
internal object CornerModelTransform {
    const val INPUT_SIZE = 128
    const val CHANNELS = 3
    const val INPUT_BYTES = INPUT_SIZE * INPUT_SIZE * CHANNELS * Float.SIZE_BYTES
    const val NUM_KEYPOINTS = 7
    const val OUTPUT_FLOATS = 1 + NUM_KEYPOINTS * 2
    const val CROP_RADIUS_MULTIPLIER = dev.cubentia.app.camera.CORNER_MODEL_CROP_RADIUS_MULTIPLIER
    const val DEFAULT_PRESENCE_THRESHOLD = 0.55f

    /** Maps upright normalized crop coords `(u, v) in [0, 1]^2` to frame pixels `(x, y)` using the inverse of ViewfinderGrid rotation. */
    fun cropToFrame(
        u: Double,
        v: Double,
        cx: Double,
        cy: Double,
        half: Double,
        rotationDegrees: Int,
    ): CornerPoint {
        val dx = (u - 0.5) * 2.0 * half
        val dy = (v - 0.5) * 2.0 * half
        val (fx, fy) =
            when (Math.floorMod(rotationDegrees, 360)) {
                90 -> dy to -dx
                180 -> -dx to -dy
                270 -> -dy to dx
                else -> dx to dy
            }
        return CornerPoint(cx + fx, cy + fy)
    }

    /** Exact inverse of [cropToFrame]: maps frame pixels `(x, y)` back to upright normalized crop coords `(u, v)`. */
    fun frameToCrop(
        x: Double,
        y: Double,
        cx: Double,
        cy: Double,
        half: Double,
        rotationDegrees: Int,
    ): CornerPoint {
        val fx = x - cx
        val fy = y - cy
        val (dx, dy) =
            when (Math.floorMod(rotationDegrees, 360)) {
                90 -> -fy to fx
                180 -> -fx to -fy
                270 -> fy to -fx
                else -> fx to fy
            }
        return CornerPoint(0.5 + dx / (2.0 * half), 0.5 + dy / (2.0 * half))
    }

    /**
     * Extracts the upright `128×128×3` `float32` RGB `[0, 1]` crop centered on [guide] into [output]
     * (repositioning [output] to 0 before writing).
     */
    fun fillRgbInput(
        frame: YuvFrame,
        guide: CornerGuide,
        output: ByteBuffer,
    ) {
        output.clear()
        val center = guide.vertices().last()
        val half = guide.radius * CROP_RADIUS_MULTIPLIER
        val rot = Math.floorMod(guide.rotationDegrees, 360)
        val minX = frame.left
        val maxX = (frame.right - 1).coerceAtLeast(minX)
        val minY = frame.top
        val maxY = (frame.bottom - 1).coerceAtLeast(minY)
        val yPlane = frame.y
        val uPlane = frame.u
        val vPlane = frame.v
        val invSize = 1.0 / INPUT_SIZE

        for (row in 0 until INPUT_SIZE) {
            val v = (row + 0.5) * invSize
            val dy = (v - 0.5) * 2.0 * half
            for (col in 0 until INPUT_SIZE) {
                val u = (col + 0.5) * invSize
                val dx = (u - 0.5) * 2.0 * half
                val fx: Double
                val fy: Double
                when (rot) {
                    90 -> {
                        fx = dy
                        fy = -dx
                    }

                    180 -> {
                        fx = -dx
                        fy = -dy
                    }

                    270 -> {
                        fx = -dy
                        fy = dx
                    }

                    else -> {
                        fx = dx
                        fy = dy
                    }
                }
                val px = (center.x + fx).roundToInt().coerceIn(minX, maxX)
                val py = (center.y + fy).roundToInt().coerceIn(minY, maxY)
                val yy = yPlane.at(px, py).toFloat()
                val uu = uPlane.at(px shr 1, py shr 1).toFloat() - 128f
                val vv = vPlane.at(px shr 1, py shr 1).toFloat() - 128f
                val r = ((yy + 1.402f * vv) / 255f).coerceIn(0f, 1f)
                val g = ((yy - 0.344136f * uu - 0.714136f * vv) / 255f).coerceIn(0f, 1f)
                val b = ((yy + 1.772f * uu) / 255f).coerceIn(0f, 1f)
                output.putFloat(r)
                output.putFloat(g)
                output.putFloat(b)
            }
        }
        output.rewind()
    }

    /**
     * Decodes `[presence, T.x, T.y, ..., C.x, C.y]` model output into frame-pixel face quads, guarded by
     * [presenceThreshold] and [CornerLocator.isValidCubeGeometry]. Returns `null` on non-finite values, on presence or
     * any normalized keypoint coordinate outside the closed range `[0, 1]` (never clamped), on low presence, or on
     * invalid geometry. The model head is a sigmoid (`tools/corner-model/train_corner_model.py`), so a value outside
     * `[0, 1]` means a wrong or mis-exported model and is rejected rather than clamped.
     */
    fun decode(
        output: FloatArray,
        guide: CornerGuide,
        presenceThreshold: Float = DEFAULT_PRESENCE_THRESHOLD,
    ): CornerLocator.Location? {
        if (output.size < OUTPUT_FLOATS) return null
        for (i in 0 until OUTPUT_FLOATS) {
            if (!output[i].isFinite()) return null
        }
        for (i in 0 until OUTPUT_FLOATS) {
            if (output[i] !in 0f..1f) return null
        }
        val presence = output[0]
        if (presence < presenceThreshold) return null
        val center = guide.vertices().last()
        val half = guide.radius * CROP_RADIUS_MULTIPLIER
        val rot = Math.floorMod(guide.rotationDegrees, 360)
        val points =
            List(NUM_KEYPOINTS) { idx ->
                val u = output[1 + idx * 2].toDouble()
                val v = output[2 + idx * 2].toDouble()
                cropToFrame(u, v, center.x, center.y, half, rot)
            }
        if (!CornerLocator.isValidCubeGeometry(points, guide)) return null
        return CornerLocator.Location(guide.quads(points), presence.toDouble())
    }
}
