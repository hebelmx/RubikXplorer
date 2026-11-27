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
    /** Story 10.34: the letterbox into the square model input (model-input = upright × [inputScale] + pad); 0 when unknown. */
    val inputScale: Double = 0.0,
    val inputPadLeft: Int = 0,
    val inputPadTop: Int = 0,
    /**
     * GH #403: the detector could not read this frame (its model input could not be filled, or conversion or peak
     * decoding threw); a skipped frame, not an empty view. The pipeline leaves the mapper untouched for it. A degenerate
     * crop or an unavailable detector returns [EMPTY] instead.
     */
    val skipped: Boolean = false,
) {
    val centers: List<Point2D> get() = peaks.map(StickerCenterPeak::toPoint2D)

    companion object {
        val EMPTY = StickerCenterDetection(emptyList(), 0, 0, 0.0)

        /** GH #403: a frame whose model input could not be filled, or whose conversion or decoding threw; not an empty view. */
        val SKIPPED = StickerCenterDetection(emptyList(), 0, 0, 0.0, skipped = true)
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
     * contract, an inference failed or the detector was closed (GH #404), and for the rest of the app process (nothing
     * retries). A state of its own, never an empty detection: a skipped frame, a degenerate crop and a frame with no
     * cube leave it false. Safe to read from any thread, with or without a frame.
     */
    val unavailable: Boolean

    /**
     * Detects sticker centers in [frame]. A frame whose model input could not be filled, or whose conversion or peak
     * decoding threw, returns [StickerCenterDetection.SKIPPED] (GH #403), so the pipeline does not take it for a view
     * with no cube. A degenerate crop or an unavailable detector still returns [StickerCenterDetection.EMPTY].
     */
    fun detect(
        frame: YuvFrame,
        rotationDegrees: Int,
        viewWidth: Int = 0,
        viewHeight: Int = 0,
    ): StickerCenterDetection

    /**
     * Story 10.34: copies the model input of the last [detect] into [into] (`INPUT_SIZE² × 3` RGB bytes); false when
     * there is none or the detector does not expose it. Same thread as [detect].
     */
    fun copyModelInput(into: ByteArray): Boolean = false

    /**
     * GH #397: a frame read past [detect] that the pipeline could not sample (a plane short in rows the model input never
     * read). Counted with the detector's own bad frames, so one failed-frame count covers both. Same thread as [detect];
     * never throws.
     */
    fun countFailedFrame() = Unit
}
