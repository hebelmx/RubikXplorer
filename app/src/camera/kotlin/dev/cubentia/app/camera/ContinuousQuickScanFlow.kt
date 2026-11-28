package dev.cubentia.app.camera

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CalibrationOffer
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.ScanAssembler
import dev.cubentia.domain.recognition.ScanReview
import dev.cubentia.domain.recognition.SelfCalibration
import dev.cubentia.domain.recognition.StickerClassifier
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.Point2D
import dev.cubentia.domain.text.Text

/** One outline per tracked quad: its HOME face once [anchored], else the fragment's local face label. */
internal data class OutlineKey(
    val face: Face,
    val anchored: Boolean,
)

internal data class TrackedOutline(
    val corners: List<Point2D>,
    val missingFrames: Int = 0,
)

internal data class CountAnnouncement(
    val count: Int,
    val serial: Int,
)

internal data class ContinuousQuickScanFlow(
    val snapshot: ContinuousScanSnapshot? = null,
    val outlines: Map<OutlineKey, TrackedOutline> = emptyMap(),
    val announcement: CountAnnouncement? = null,
    val reviewAtMs: Long? = null,
    /** GH #409: the latest frame's debug overlay; null unless the debug view is on (never in release). */
    val debugOverlay: ContinuousDebugOverlay? = null,
    /** GH #415: whether "Move the cube into the frame." is on (see [cueText]); it changes only after [FRAMING_CUE_FRAMES]. */
    val framingShown: Boolean = false,
    /** GH #415: consecutive analysed frames whose mostly-out-of-view reading differs from [framingShown]. */
    val framingRun: Int = 0,
) {
    val count: Int get() =
        when {
            snapshot is ContinuousScanSnapshot.Complete -> 6
            snapshot?.globalAnchored == true -> snapshot.mappedFacesCount
            else -> 0
        }

    /** The mapper's cue (pulses and the hand-over read it); the cue line shows [cueText]. */
    val cue get() = snapshot?.cue ?: ContinuousScanCue.BringIntoView

    /**
     * GH #415: the one cue line, in the order of SCP 2026-11-27 quickscan-framing-crop section 3.4: a layer turned
     * mid-scan, all 54 verified, "Bring the cube into view." (nothing tracked anywhere in the frame), then "Move the cube
     * into the frame." while [framingShown], then the mapper's other cues in their own order.
     */
    val cueText: Text get() {
        val mapper = cue
        val first =
            mapper == ContinuousScanCue.LayerTwistDetected ||
                mapper == ContinuousScanCue.AllVerified ||
                mapper == ContinuousScanCue.BringIntoView ||
                snapshot?.twistDetected == true ||
                snapshot is ContinuousScanSnapshot.Complete
        return if (!first && framingShown) Text(MOVE_INTO_FRAME_KEY) else mapper.toText()
    }
    val confirmClose get() = count > 0

    /**
     * Whether *Review and Correct* (PO clarification Q8) is on the screen: the shown count is more than [sidesFraction]
     * of the six sides and the stickers set in the shown net are more than [stickersFraction] of the 54. Also under a
     * latched twist (judged on the kept net); never before anchoring, nor once the scan is complete (Review opens by
     * itself then).
     */
    fun reviewAndCorrectOffered(
        sidesFraction: Double = REVIEW_SIDES_FRACTION,
        stickersFraction: Double = REVIEW_STICKERS_FRACTION,
    ): Boolean {
        val shown = snapshot ?: return false
        if (shown is ContinuousScanSnapshot.Complete || !shown.globalAnchored) return false
        return count > Face.entries.size * sidesFraction && shown.committedCount > Facelets.SIZE * stickersFraction
    }

    /**
     * The Scan Review indices of the shown snapshot's conflicted stickers (HOME face and cell, as the export uses): the
     * stickers an early Review checks first. The export itself carries them only as blanks.
     */
    val conflictedReviewIndices: Set<Int> get() =
        snapshot?.conflictedSlots.orEmpty().mapTo(sortedSetOf()) { it.face.ordinal * CELLS_PER_FACE + it.cellIndex }

    fun next(
        frame: ContinuousFrame,
        nowMs: Long,
    ): ContinuousQuickScanFlow {
        val snap = frame.snapshot
        val tracked = mutableMapOf<OutlineKey, TrackedOutline>()
        // No outline on a twist or once the scan is complete (the analysis has stopped: a kept outline would freeze on screen).
        if (!snap.twistDetected && snap !is ContinuousScanSnapshot.Complete) {
            for ((face, old) in outlines) {
                if (old.missingFrames < 2) tracked[face] = old.copy(missingFrames = old.missingFrames + 1)
            }
            for (quad in frame.viewOverlays) {
                val local = OutlineKey(quad.localFace, anchored = false)
                val face = quad.face?.let { OutlineKey(it, anchored = true) } ?: local
                // A quad that just became anchored continues from its unanchored outline, which goes at once.
                if (face != local) tracked.remove(local)
                // GH #415: a face read wholly outside the visible preview has no outline (its stickers still count).
                if (ViewClip.visibleArea(quad.viewCorners) <= 0.0) {
                    tracked.remove(face)
                    continue
                }
                val old = (outlines[face] ?: outlines[local].takeIf { face != local })?.corners?.takeIf { it.size == quad.viewCorners.size }
                val corners =
                    if (old == null) {
                        quad.viewCorners
                    } else {
                        old.zip(quad.viewCorners) { a, b ->
                            Point2D((a.x + b.x) / 2, (a.y + b.y) / 2)
                        }
                    }
                tracked[face] = TrackedOutline(corners)
            }
        }
        // GH #415: a skipped frame neither advances nor resets the framing count.
        var shown = framingShown
        var run = framingRun
        if (frame.analysed) {
            if (ViewClip.mostlyOutOfView(frame.viewOverlays.map { it.viewCorners }) != shown) {
                run++
                if (run >= FRAMING_CUE_FRAMES) {
                    shown = !shown
                    run = 0
                }
            } else {
                run = 0
            }
        }
        val next =
            copy(
                snapshot = snap,
                outlines = tracked,
                debugOverlay = frame.debug,
                reviewAtMs = if (snap is ContinuousScanSnapshot.Complete) reviewAtMs ?: (nowMs + 1000L) else null,
                framingShown = shown,
                framingRun = run,
            )
        return if (next.count > count) next.copy(announcement = CountAnnouncement(next.count, (announcement?.serial ?: 0) + 1)) else next
    }

    enum class Pulse { Tick, Confirm, Reject }

    fun pulsesSince(before: ContinuousQuickScanFlow): List<Pulse> =
        buildList {
            if (count > before.count) add(Pulse.Tick)
            if (snapshot is ContinuousScanSnapshot.Complete && before.snapshot !is ContinuousScanSnapshot.Complete) add(Pulse.Confirm)
            if (cue != before.cue &&
                (cue is ContinuousScanCue.ResolveConflict || cue == ContinuousScanCue.LayerTwistDetected)
            ) {
                add(Pulse.Reject)
            }
        }

    /**
     * The stickers *Enter colors instead* seeds the Editor with, decided from the fresh export [handoff] (never from the
     * shown count, which can be one frame older): its confirmed stickers when it is anchored and holds a face, else none.
     */
    fun fallback(handoff: ContinuousScanHandoff) =
        handoff.toFallbackStickers().takeIf {
            handoff.globalAnchored &&
                handoff.faces.isNotEmpty()
        }

    /**
     * The faces *Scan face by face* keeps from the fresh export [handoff]: none when it is unanchored, else only those
     * whose nine stickers the mapper committed. At any number of mapped sides Guided is asked for every other face: the
     * absent ones and those holding a conflicted or still-unread sticker (below six sides the mapper commits centres
     * only, so that is every mapped face); when none is, the face the conflict cue names.
     */
    fun keptForGuided(handoff: ContinuousScanHandoff): ContinuousScanHandoff {
        if (!handoff.globalAnchored) return handoff.copy(faces = emptyMap())
        if (snapshot is ContinuousScanSnapshot.Complete) return handoff
        val requested = snapshot?.conflictedSlots.orEmpty().mapTo(mutableSetOf()) { it.face }
        handoff.faces.forEach { (face, data) -> if (data.colors.any { it == null }) requested.add(face) }
        if (requested.isEmpty()) (cue as? ContinuousScanCue.ResolveConflict)?.let { requested.add(it.face) }
        return handoff.copy(faces = handoff.faces - requested)
    }

    companion object {
        /** *Review and Correct* needs more than this fraction of the six sides mapped. */
        const val REVIEW_SIDES_FRACTION = 0.5

        /** *Review and Correct* needs more than this fraction of the 54 stickers set in the shown net. */
        const val REVIEW_STICKERS_FRACTION = 0.5
        private const val CELLS_PER_FACE = 9

        /**
         * GH #415: the tracked faces are "mostly out of view" when more than this fraction of the summed area of all
         * tracked quads of a frame lies outside the visible preview.
         */
        const val MOSTLY_OUT_OF_VIEW_FRACTION = 0.5

        /** GH #415: the framing cue shows after, and clears after, this many consecutive analysed frames. */
        const val FRAMING_CUE_FRAMES = 3

        /** GH #415: "Move the cube into the frame." */
        const val MOVE_INTO_FRAME_KEY = "msg.quick-scan.continuous.move-into-frame"

        fun countText(count: Int) = Text("msg.quick-scan.sides-mapped", mapOf("n" to count))
    }
}

/**
 * GH #415: polygon helpers on the visible preview, `[0, 1]²` in view fractions. View fractions are an affine image of
 * frame pixels, so area ratios computed here hold in pixels too.
 */
internal object ViewClip {
    /** The absolute (shoelace) area of [points]; 0 below three points. */
    fun area(points: List<Point2D>): Double {
        if (points.size < 3) return 0.0
        var twice = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            twice += a.x * b.y - b.x * a.y
        }
        return kotlin.math.abs(twice) / 2
    }

    /** [points] clipped to `[0, 1]²` (Sutherland–Hodgman); empty when nothing of it lies inside. */
    fun clipToView(points: List<Point2D>): List<Point2D> {
        var poly = points
        for (edge in 0 until 4) {
            if (poly.isEmpty()) return poly
            val inside: (Point2D) -> Boolean =
                when (edge) {
                    0 -> { p -> p.x >= 0.0 }
                    1 -> { p -> p.x <= 1.0 }
                    2 -> { p -> p.y >= 0.0 }
                    else -> { p -> p.y <= 1.0 }
                }
            val bound = if (edge % 2 == 0) 0.0 else 1.0
            val vertical = edge < 2
            val out = ArrayList<Point2D>(poly.size + 2)
            for (i in poly.indices) {
                val cur = poly[i]
                val prev = poly[(i + poly.size - 1) % poly.size]
                val curIn = inside(cur)
                if (curIn != inside(prev)) out += crossing(prev, cur, bound, vertical)
                if (curIn) out += cur
            }
            poly = out
        }
        return poly
    }

    private fun crossing(
        a: Point2D,
        b: Point2D,
        bound: Double,
        vertical: Boolean,
    ): Point2D =
        if (vertical) {
            val t = (bound - a.x) / (b.x - a.x)
            Point2D(bound, a.y + t * (b.y - a.y))
        } else {
            val t = (bound - a.y) / (b.y - a.y)
            Point2D(a.x + t * (b.x - a.x), bound)
        }

    /** The area of [points] inside the visible preview. */
    fun visibleArea(points: List<Point2D>): Double = area(clipToView(points))

    /**
     * Whether more than [ContinuousQuickScanFlow.MOSTLY_OUT_OF_VIEW_FRACTION] of the summed area of [quads] lies outside
     * the visible preview: `1 - Σ area(clip(q)) / Σ area(q)`; false with no quads or no area.
     */
    fun mostlyOutOfView(quads: List<List<Point2D>>): Boolean {
        val total = quads.sumOf(::area)
        if (!(total > 0.0)) return false
        val outside = 1.0 - quads.sumOf(::visibleArea) / total
        return outside > ContinuousQuickScanFlow.MOSTLY_OUT_OF_VIEW_FRACTION
    }
}

internal fun handoverReview(
    kept: ContinuousScanHandoff,
    added: List<FaceSample>,
    saved: CalibrationCenters?,
): GuidedScanPhase.Review {
    val result = ScanAssembler.assembleHandover(kept, added, saved, preserveUnread = true)
    val centers = handoverCenters(kept, added, saved)
    val review = ScanReview.from(result, centers)
    return GuidedScanPhase.Review(review, CalibrationOffer.offered(added, centers, review), samples = added, calibration = centers)
}

/**
 * The six centres a hand-over review is read with, so a later per-face retake can calibrate without six Guided samples:
 * the hand-off's own calibration, else the kept faces' measured centres (then [saved]) completed by the [added] Guided
 * samples, else [saved].
 */
private fun handoverCenters(
    kept: ContinuousScanHandoff,
    added: List<FaceSample>,
    saved: CalibrationCenters?,
): CalibrationCenters? {
    kept.calibration?.takeIf(StickerClassifier::isValid)?.let { return it }
    if (added.isEmpty()) return saved
    val known = LinkedHashMap<Color, Lab>()
    if (kept.globalAnchored) {
        kept.faces.forEach { (face, data) ->
            data.labs[FaceSample.CENTER_INDEX]?.takeIf { it.isValid() }?.let { known[Color.home(face)] = it }
        }
    }
    saved?.centers?.forEach { (color, lab) -> known.putIfAbsent(color, lab) }
    val fallback = known.takeIf { it.isNotEmpty() }?.let(::CalibrationCenters)
    return (SelfCalibration.from(added, fallback) as? SelfCalibration.Calibrated)?.calibration?.takeIf(StickerClassifier::isValid) ?: saved
}
