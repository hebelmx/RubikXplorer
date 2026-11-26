package dev.cubentia.app.camera

import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.Point2D

/** Live tracked face quad projected to normalized `[0..1]` `PreviewView` coordinates. */
internal data class ContinuousOverlayQuad(
    val face: Face?,
    val localFace: Face,
    val viewCorners: List<Point2D>,
)

internal data class ContinuousFrame(
    val snapshot: ContinuousScanSnapshot,
    val viewOverlays: List<ContinuousOverlayQuad>,
)

/** Session-scoped mapper. All calls belong to the scan's single analyzer executor. */
internal interface ContinuousScanEngine {
    fun processFrame(
        frame: YuvFrame,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): ContinuousFrame

    fun reset()

    fun export(): ContinuousScanExport
}

internal fun interface ContinuousScanEngineFactory {
    fun create(): ContinuousScanEngine
}
