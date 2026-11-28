package dev.cubentia.app.camera.continuous

import dev.cubentia.app.camera.ContinuousDebugOverlay
import dev.cubentia.app.camera.ContinuousFrameTap
import dev.cubentia.app.camera.ContinuousOverlayQuad
import dev.cubentia.app.camera.DebugLattice
import dev.cubentia.app.camera.DebugPeak
import dev.cubentia.app.camera.QuickScanDebugView
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
import java.util.Locale

/** Per-frame result of the continuous Quick Scan pipeline (detector -> lattice -> sampler -> mapper). */
internal data class ContinuousPipelineFrame(
    val snapshot: ContinuousScanSnapshot,
    val detection: StickerCenterDetection,
    val fittedFaces: List<FittedFace>,
    val viewOverlays: List<ContinuousOverlayQuad>,
    /** GH #409: the debug overlay; null unless the pipeline's debug view is on. */
    val debug: ContinuousDebugOverlay? = null,
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
    /** GH #406: per-stage timings (debug only); with null no clock is read. */
    private val timings: ContinuousStageTimings? = ContinuousStageTimings.forBuild(),
    /** GH #409: whether to build the debug overlay of each frame; false in release by the const gate. */
    private val debugView: () -> Boolean = QuickScanDebugView::enabled,
) {
    /** Story 10.34: the debug Quick Scan session recording's neutral frame tap; null unless a session records. */
    var tap: ContinuousFrameTap? = null

    /** GH #390: the detector's own state, readable from any thread with or without a frame. */
    val detectorUnavailable get() = detector.unavailable

    private var lastSnapshot: ContinuousScanSnapshot? = null

    // GH #406: the clock at [analyzerStart], -1 when no analyzer interval is open.
    private var analyzerStartNs = -1L

    // GH #406: this frame's stage durations in ns (-1: the stage did not run), primitives so timing never allocates.
    private val clock: NanoClock? = timings?.clock
    private var detectorNs = -1L
    private var inferenceNs = -1L
    private var fitNs = -1L
    private var sampleNs = -1L
    private var mapNs = -1L
    private var fittedCount = 0
    private var observationCount = 0

    /** Reads [clock] only when timings are on; never boxes. */
    private fun now(): Long {
        val c = clock
        return if (c == null) 0L else c.nanoTime()
    }

    fun processFrame(
        frameIndex: Int,
        frame: YuvFrame,
        rotationDegrees: Int,
        viewWidth: Int = 0,
        viewHeight: Int = 0,
    ): ContinuousPipelineFrame {
        if (timings == null) return analyse(frameIndex, frame, rotationDegrees, viewWidth, viewHeight)
        detectorNs = -1L
        inferenceNs = -1L
        fitNs = -1L
        sampleNs = -1L
        mapNs = -1L
        fittedCount = 0
        observationCount = 0
        return analyse(frameIndex, frame, rotationDegrees, viewWidth, viewHeight)
    }

    /**
     * GH #406: opens the frame's analyzer interval, before the analyzer converts the image; [analyzerEnd] closes it after
     * the frame is delivered, so the analyzer stage covers conversion, [processFrame], `frameDone` and delivery.
     * Without timings neither reads the clock.
     */
    fun analyzerStart() {
        val t = timings ?: return
        analyzerStartNs = t.clock.nanoTime()
    }

    /** GH #406: closes the interval [analyzerStart] opened and records the frame's stages; without one it does nothing. */
    fun analyzerEnd() {
        val t = timings ?: return
        val start = analyzerStartNs
        if (start < 0L) return
        analyzerStartNs = -1L
        t.record(start, t.clock.nanoTime(), detectorNs, inferenceNs, fitNs, sampleNs, mapNs, fittedCount, observationCount)
    }

    /** GH #406 (debug diagnostics): the body of the `quickScan timing run` line for this pipeline's life; null without timings or frames. */
    fun timingRunLine(): String? = timings?.runLine()

    private fun analyse(
        frameIndex: Int,
        frame: YuvFrame,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): ContinuousPipelineFrame {
        if (detectorUnavailable || frame.left < 0 || frame.top < 0 || frame.width <= 0 || frame.height <= 0) {
            return skippedFrame(frameIndex)
        }
        val detectStart = now()
        val detection = detector.detect(frame, rotationDegrees, viewWidth, viewHeight)
        val detectEnd = now()
        // GH #390: the frame that latched the detector is not an empty view of the cube; the map stays as it was.
        if (detectorUnavailable) return skippedFrame(frameIndex)
        // GH #403: a frame the detector could not read is not "cube lost"; it already counted it, so no second count here.
        if (detection.skipped) return skippedFrame(frameIndex)
        detectorNs = detectEnd - detectStart
        inferenceNs = (detection.inferenceMs * NANOS_PER_MS).toLong()
        val fitStart = now()
        val fittedFaces = FaceLatticeFitter.fit(detection.centers)
        val sampleStart = now()
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
        val mapStart = now()
        val snapshot = mapper.processFrame(frameIndex, observations)
        val mapEnd = now()
        fitNs = sampleStart - fitStart
        sampleNs = mapStart - sampleStart
        mapNs = mapEnd - mapStart
        fittedCount = fittedFaces.size
        observationCount = observations.size
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
        val debug =
            if (debugView()) {
                debugOverlay(frame, rotationDegrees, viewWidth, viewHeight, detection, fittedFaces, observations.size, snapshot)
            } else {
                null
            }
        return ContinuousPipelineFrame(
            snapshot = snapshot,
            detection = detection,
            fittedFaces = fittedFaces,
            viewOverlays = overlays,
            debug = debug,
        )
    }

    /**
     * GH #409: every peak with its score, each fitted face's nine cells mapped upright → view as the outlines are, and the
     * counts line. A cell is an inlier when an inlier peak of the fit lies nearest it ([FittedFace] keeps no cell index).
     */
    private fun debugOverlay(
        frame: YuvFrame,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
        detection: StickerCenterDetection,
        fittedFaces: List<FittedFace>,
        sampled: Int,
        snapshot: ContinuousScanSnapshot,
    ): ContinuousDebugOverlay {
        val peaks = detection.peaks.map { DebugPeak(it.viewX, it.viewY, it.score) }
        val lattices =
            fittedFaces.map { face ->
                val inliers = BooleanArray(face.gridCenters.size)
                for (index in face.inlierIndices) {
                    val peak = detection.peaks.getOrNull(index) ?: continue
                    val nearest =
                        face.gridCenters.indices.minByOrNull { i ->
                            val dx = face.gridCenters[i].x - peak.x
                            val dy = face.gridCenters[i].y - peak.y
                            dx * dx + dy * dy
                        } ?: continue
                    inliers[nearest] = true
                }
                DebugLattice(
                    centers =
                        face.gridCenters.map { c ->
                            val (vx, vy) =
                                StickerCenterModelTransform.uprightToViewFraction(
                                    ux = c.x,
                                    uy = c.y,
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
                    inliers = inliers.toList(),
                )
            }
        val counts =
            buildList {
                add("peaks=${detection.peaks.size}")
                add("fitted=${fittedFaces.size}")
                add("sampled=$sampled")
                add("kept=${mapper.lastPlausibleQuadCount}")
                add("tracked=${snapshot.trackedQuads.size}")
                if (timings != null) {
                    add("detectMs=${ms(detectorNs)}")
                    add("fitMs=${ms(fitNs)}")
                    add("sampleMs=${ms(sampleNs)}")
                    add("mapMs=${ms(mapNs)}")
                }
            }.joinToString(" ")
        return ContinuousDebugOverlay(peaks, lattices, counts)
    }

    private fun ms(ns: Long): String = if (ns < 0L) "-" else String.format(Locale.ROOT, "%.1f", ns / NANOS_PER_MS)

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
            debug = if (debugView()) ContinuousDebugOverlay.SKIPPED else null,
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

    private companion object {
        const val NANOS_PER_MS = 1_000_000.0
    }
}
