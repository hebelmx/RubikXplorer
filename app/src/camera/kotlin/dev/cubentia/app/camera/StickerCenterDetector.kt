package dev.cubentia.app.camera

import dev.cubentia.domain.recognition.continuous.Point2D

/**
 * One detected sticker center from the continuous Quick Scan detector (Story 10.31, AD-9).
 *
 * ([x], [y]) are in upright crop pixel coordinates (matching [dev.cubentia.domain.recognition.continuous.FaceLatticeFitter]);
 * ([viewX], [viewY]) are normalized `[0..1]` `PreviewView` coordinates mapped through [ViewfinderGrid.frameToViewFraction].
 */
internal data class StickerCenterPeak(
    val x: Double,
    val y: Double,
    val score: Float,
    val viewX: Double,
    val viewY: Double,
) {
    fun toPoint2D(): Point2D = Point2D(x, y)
}

/** Per-frame sticker-center detection output crossing from the continuous model adapter to the camera pipeline. */
internal data class StickerCenterDetection(
    val peaks: List<StickerCenterPeak>,
    val uprightWidth: Int,
    val uprightHeight: Int,
    val inferenceMs: Double = 0.0,
) {
    val centers: List<Point2D> get() = peaks.map(StickerCenterPeak::toPoint2D)

    companion object {
        val EMPTY = StickerCenterDetection(emptyList(), 0, 0, 0.0)
    }
}

/**
 * Optional seam for continuous Quick Scan sticker-center detection (AD-9, Story 10.31).
 *
 * Bound by `StickerCenterDetectorModule` only when `QUICK_SCAN` is above OFF (`app/src/camera/continuous/`);
 * unbound otherwise so variants without continuous Quick Scan ship no LiteRT model or dependency.
 */
internal interface StickerCenterDetector {
    /**
     * Whether the detector cannot run (GH #390): true from the moment the model failed to load, did not match the tensor
     * contract or an inference failed, and for the rest of the app process (nothing retries). A state of its own, never
     * an empty detection: a skipped frame, a degenerate crop and a frame with no cube leave it false. Safe to read from
     * any thread, with or without a frame.
     */
    val unavailable: Boolean

    fun detect(
        frame: YuvFrame,
        rotationDegrees: Int,
        viewWidth: Int = 0,
        viewHeight: Int = 0,
    ): StickerCenterDetection
}
