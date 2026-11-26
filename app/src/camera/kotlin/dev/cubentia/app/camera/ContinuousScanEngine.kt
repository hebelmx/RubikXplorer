package dev.cubentia.app.camera

import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.Point2D
import dev.cubentia.domain.recognition.continuous.QuadObservation

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

/**
 * Neutral per-frame tap of the continuous pipeline (Story 10.34, AD-9 Frames): called on the analyzer thread after the
 * mapper with the frame's neutral observations, snapshot and detection, and the detector's model input when
 * [wantsModelInput] asked for it. Shipped code only invokes it; the only implementation is the debug recorder's writer,
 * reached through an optional binding. The Solve-tab scan passes none.
 */
internal interface ContinuousFrameTap {
    fun wantsModelInput(frameIndex: Int): Boolean

    /** [modelInput] is a reused buffer valid only for this call; an implementation copies what it keeps. */
    fun onFrame(
        frameIndex: Int,
        observations: List<QuadObservation>,
        snapshot: ContinuousScanSnapshot,
        detection: StickerCenterDetection,
        modelInput: ByteArray?,
    )
}

/** The detector a continuous engine runs (Story 10.34): model file and SHA-256, input side, delegate and threads. */
internal data class ContinuousModelIdentity(
    val file: String,
    val sha256: String,
    val inputSize: Int,
    val delegate: String,
    val threads: Int,
)

internal fun interface ContinuousScanEngineFactory {
    /** A new scan session; [tap] (debug recorder only) sees every frame. */
    fun create(tap: ContinuousFrameTap?): ContinuousScanEngine

    fun create(): ContinuousScanEngine = create(null)

    /** The detector the engines run, when known. */
    val modelIdentity: ContinuousModelIdentity? get() = null
}
