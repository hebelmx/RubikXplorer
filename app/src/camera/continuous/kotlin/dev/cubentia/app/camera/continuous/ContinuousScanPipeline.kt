package dev.cubentia.app.camera.continuous

import dev.cubentia.app.camera.ContinuousFrameTap
import dev.cubentia.app.camera.ContinuousOverlayQuad
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.StickerCenterDetector
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.continuous.ContinuousFaceletMapper
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.FaceLatticeFitter
import dev.cubentia.domain.recognition.continuous.FittedFace
import dev.cubentia.domain.recognition.continuous.Point2D
import dev.cubentia.domain.recognition.continuous.UnreadSlot

/** Per-frame result of the continuous Quick Scan pipeline (detector -> lattice -> sampler -> mapper). */
internal data class ContinuousPipelineFrame(
    val snapshot: ContinuousScanSnapshot,
    val detection: StickerCenterDetection,
    val fittedFaces: List<FittedFace>,
    val viewOverlays: List<ContinuousOverlayQuad>,
)

/**
 * Connects [StickerCenterDetector] → [FaceLatticeFitter.fit] → [ContinuousFaceSampler.sample] →
 * [ContinuousFaceletMapper.processFrame] and maps tracked quads to `PreviewView` fractions via
 * [dev.cubentia.app.camera.ViewfinderGrid.frameToViewFraction] (Story 10.31, AC4).
 *
 * Single-threaded on `analyzerExecutor`; holds no frames between calls (AD-13).
 */
internal class ContinuousScanPipeline(
    private val detector: StickerCenterDetector,
    private val sampler: ContinuousFaceSampler = ContinuousFaceSampler(),
    private val mapper: ContinuousFaceletMapper = ContinuousFaceletMapper(),
) {
    /** Story 10.34: the debug Quick Scan session recording's neutral frame tap; null unless a session records. */
    var tap: ContinuousFrameTap? = null

    /** GH #390: the detector's own state, readable from any thread with or without a frame. */
    val detectorUnavailable get() = detector.unavailable

    private var lastSnapshot: ContinuousScanSnapshot? = null

    fun processFrame(
        frameIndex: Int,
        frame: YuvFrame,
        rotationDegrees: Int,
        viewWidth: Int = 0,
        viewHeight: Int = 0,
    ): ContinuousPipelineFrame {
        if (detectorUnavailable || frame.left < 0 || frame.top < 0 || frame.width <= 0 || frame.height <= 0) {
            return skippedFrame(frameIndex)
        }
        val detection = detector.detect(frame, rotationDegrees, viewWidth, viewHeight)
        // GH #390: the frame that latched the detector is not an empty view of the cube; the map stays as it was.
        if (detectorUnavailable) return skippedFrame(frameIndex)
        // GH #403: a frame the detector could not read is not "cube lost"; it already counted it, so no second count here.
        if (detection.skipped) return skippedFrame(frameIndex)
        val fittedFaces = FaceLatticeFitter.fit(detection.centers)
        val observations =
            if (fittedFaces.isEmpty()) {
                emptyList()
            } else {
                // GH #397: a plane short in rows the detector never read is a bad frame, not the end of the scan.
                try {
                    fittedFaces.mapNotNull { face -> sampler.sample(frame, rotationDegrees, face) }
                } catch (_: Exception) {
                    detector.countFailedFrame()
                    return skippedFrame(frameIndex)
                }
            }
        val snapshot = mapper.processFrame(frameIndex, observations)
        lastSnapshot = snapshot
        tap?.let { t ->
            // One copy, straight into the tap's preallocated buffer; no allocation on the analyzer thread.
            val buffer = t.modelInputBuffer(frameIndex)
            val filled = buffer != null && copyModelInput(buffer)
            t.onFrame(frameIndex, observations, snapshot, detection, filled)
        }
        val overlays =
            snapshot.trackedQuads.map { tracked ->
                ContinuousOverlayQuad(
                    face = tracked.face,
                    localFace = tracked.localFace,
                    viewCorners =
                        tracked.quad.corners.map { corner ->
                            val (vx, vy) =
                                StickerCenterModelTransform.uprightToViewFraction(
                                    ux = corner.x,
                                    uy = corner.y,
                                    viewWidth = viewWidth,
                                    viewHeight = viewHeight,
                                    cropLeft = frame.left,
                                    cropTop = frame.top,
                                    cropRight = frame.right,
                                    cropBottom = frame.bottom,
                                    rotationDegrees = rotationDegrees,
                                )
                            Point2D(vx, vy)
                        },
                )
            }
        return ContinuousPipelineFrame(
            snapshot = snapshot,
            detection = detection,
            fittedFaces = fittedFaces,
            viewOverlays = overlays,
        )
    }

    /**
     * A frame that is not read (an invalid crop, the detector unavailable, a frame the detector could not read, GH #403,
     * or a frame the sampler cannot read, GH #397): the last snapshot again, the mapper untouched.
     */
    private fun skippedFrame(frameIndex: Int): ContinuousPipelineFrame {
        val snapshot =
            lastSnapshot ?: ContinuousScanSnapshot.Scanning(
                frameIndex = frameIndex,
                net = Face.entries.associateWith { List(9) { null } },
                conflictedSlots = emptyList(),
                trackedQuads = emptyList(),
                globalAnchored = false,
                cue = ContinuousScanCue.BringIntoView,
                committedCount = 0,
                mappedFacesCount = 0,
                invariantViolationCount = 0,
                pendingFragmentFaces = 0,
            )
        // Story 10.34: the mapper was not called, so the tap records a skipped frame, never this (stale) snapshot.
        tap?.onSkippedFrame(frameIndex)
        return ContinuousPipelineFrame(
            snapshot = snapshot,
            detection = StickerCenterDetection.EMPTY,
            fittedFaces = emptyList(),
            viewOverlays = emptyList(),
        )
    }

    /** GH #389: a failing model-input copy drops only this frame's input, never the analyzer. */
    private fun copyModelInput(buffer: ByteArray): Boolean =
        try {
            detector.copyModelInput(buffer)
        } catch (_: Exception) {
            false
        }

    fun reset() {
        lastSnapshot = null
        mapper.reset()
    }

    fun exportHandoff(): ContinuousScanHandoff = mapper.exportHandoff()

    fun export(): ContinuousScanExport = mapper.export()

    /** GH #405 (debug diagnostics): why each unread non-centre slot is unread after the latest mapped frame. */
    fun unreadSlots(): List<UnreadSlot> = mapper.unreadSlots()
}
