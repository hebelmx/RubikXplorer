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
    /** GH #409: the debug overlay of this frame; null unless [QuickScanDebugView.enabled] (never in release). */
    val debug: ContinuousDebugOverlay? = null,
)

/** GH #409: one detector peak in normalized `[0..1]` `PreviewView` coordinates, with its detector [score]. */
internal data class DebugPeak(
    val viewX: Double,
    val viewY: Double,
    val score: Float,
)

/**
 * GH #409: one fitted face's nine projected lattice cells (row-major) in `PreviewView` fractions; [inliers]`[i]` is true
 * when an inlier peak of the fit lies nearest cell i, false for a projected cell no peak matched.
 */
internal data class DebugLattice(
    val centers: List<Point2D>,
    val inliers: List<Boolean>,
)

/**
 * GH #409 (debug only): what one frame's detector and lattice fitter saw: every peak, each fitted face's lattice, and a
 * [counts] line of numbers and fixed words (`peaks= fitted= sampled= kept= tracked=`, plus stage timings when timed).
 */
internal data class ContinuousDebugOverlay(
    val peaks: List<DebugPeak>,
    val lattices: List<DebugLattice>,
    val counts: String,
) {
    companion object {
        /** A frame the pipeline did not read (detector skipped or unavailable, bad crop, sampler failure). */
        val SKIPPED = ContinuousDebugOverlay(emptyList(), emptyList(), "skipped")
    }
}

/** Session-scoped mapper. Frame processing, reset and export belong to the scan's single analyzer executor. */
internal interface ContinuousScanEngine {
    /** Process-wide detector failure, readable on any thread without starting analysis. */
    val detectorUnavailable: Boolean

    fun processFrame(
        frame: YuvFrame,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): ContinuousFrame

    /** A new scan with an empty map; it ends any recording ([record]). */
    fun reset()

    fun export(): ContinuousScanExport

    /**
     * GH #406 (debug diagnostics): the body of the `quickScan timing run` line (per-stage timings over the engine's
     * life), or null when there are none (release, or no frame analysed).
     */
    fun timingRunLine(): String? = null

    /** GH #406 (debug diagnostics): the analyzer starts a frame, before it converts the image; release does nothing. */
    fun analyzerStart() = Unit

    /** GH #406 (debug diagnostics): the analyzer ends the frame, after [frameDone] and delivery; release does nothing. */
    fun analyzerEnd() = Unit

    /**
     * Story 10.34: a new scan (as [reset]: empty map, frame index 0) whose every frame [tap] sees; null records nothing.
     * Only the debug Quick Scan session recording passes a tap.
     */
    fun record(tap: ContinuousFrameTap?) = reset()

    /**
     * Story 10.34: ends the frame [processFrame] just returned, with its sensor timestamp, the analyzer time
     * [processFrame] took and its geometry, all primitives (no allocation). Forwarded to the active [record] tap only;
     * without one it does nothing.
     */
    fun frameDone(
        sensorTimestampNs: Long,
        analyzerNs: Long,
        rotation: Int,
        imageWidth: Int,
        imageHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
    ) = Unit
}

/**
 * Neutral per-frame tap of the continuous pipeline (Story 10.34, AD-9 Frames), called on the analyzer thread. For a read
 * frame the pipeline asks [modelInputBuffer] after the mapper, fills the buffer it gets with the detector's model input,
 * then calls [onFrame]; a frame it does not read (an invalid crop, the detector unavailable, a frame the detector could
 * not read (GH #403), or a frame it cannot sample (GH #397)) leaves the mapper untouched and calls [onSkippedFrame]
 * instead. [frameDone] then ends the frame. Shipped code only invokes it; the only implementations are the debug Quick
 * Scan session recording's, reached through an optional binding. The Solve-tab scan passes none.
 */
internal interface ContinuousFrameTap {
    /**
     * A preallocated buffer (`INPUT_SIZE² × 3` bytes) the pipeline fills with frame [frameIndex]'s model input, or null when
     * the tap does not want it; it belongs to the tap and stays valid until the tap reuses it.
     */
    fun modelInputBuffer(frameIndex: Int): ByteArray?

    /** A read frame: its neutral [observations], the mapper's [snapshot], the [detection]; [modelInputFilled] when the buffer holds its input. */
    fun onFrame(
        frameIndex: Int,
        observations: List<QuadObservation>,
        snapshot: ContinuousScanSnapshot,
        detection: StickerCenterDetection,
        modelInputFilled: Boolean,
    )

    /** A frame the pipeline did not read: no observation, and the mapper was not called. */
    fun onSkippedFrame(frameIndex: Int)

    /** See [ContinuousScanEngine.frameDone]. */
    fun frameDone(
        sensorTimestampNs: Long,
        analyzerNs: Long,
        rotation: Int,
        imageWidth: Int,
        imageHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
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
    /** A new scan session (Story 10.34: a recording one through [ContinuousScanEngine.record]). */
    fun create(): ContinuousScanEngine

    /** The detector the engines run, when known (Story 10.34, for the session manifest). */
    val modelIdentity: ContinuousModelIdentity? get() = null
}
