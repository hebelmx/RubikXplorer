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
) {
    val count: Int get() =
        when {
            snapshot is ContinuousScanSnapshot.Complete -> 6
            snapshot?.globalAnchored == true -> snapshot.mappedFacesCount
            else -> 0
        }
    val cue get() = snapshot?.cue ?: ContinuousScanCue.BringIntoView
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
        val next =
            copy(
                snapshot = snap,
                outlines = tracked,
                reviewAtMs = if (snap is ContinuousScanSnapshot.Complete) reviewAtMs ?: (nowMs + 1000L) else null,
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

        fun countText(count: Int) = Text("msg.quick-scan.sides-mapped", mapOf("n" to count))
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
