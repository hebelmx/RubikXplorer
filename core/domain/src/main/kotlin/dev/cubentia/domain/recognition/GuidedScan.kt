package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face

/**
 * One Guided Scan prompt: show the [centerColor] center to the camera with the [upNeighborColor] center on top. [face]
 * is the face whose home color is [centerColor].
 */
data class ScanStep(
    val centerColor: Color,
    val upNeighborColor: Color,
) {
    val face: Face get() = Face.entries.first { Color.home(it) == centerColor }
}

/**
 * The fixed Guided 6-face Scan order (Story 10.2). Every step shows its face exactly as the Kociemba net draws it, so
 * `ScanAssembler` turns none of them: white on top while the four side faces go round, then white (blue on top), then
 * yellow (green on top).
 */
object GuidedScanPlan {
    val steps: List<ScanStep> =
        listOf(
            ScanStep(Color.GREEN, Color.WHITE),
            ScanStep(Color.RED, Color.WHITE),
            ScanStep(Color.BLUE, Color.WHITE),
            ScanStep(Color.ORANGE, Color.WHITE),
            ScanStep(Color.WHITE, Color.BLUE),
            ScanStep(Color.YELLOW, Color.GREEN),
        )
}

/**
 * What the camera adapter measured on one analysis frame, inside the grid only: the nine cell readings in pixel
 * row-major order (null when a cell had no reading), the mean luma (Y, 0–255), [glareFraction]: the largest share of
 * one cell's pixels in a specular spot (clipped, Y ≥ 250, near-neutral pixels inside a saturated sticker color; a white
 * sticker, also one mixed with black seam or body, is never glare), the
 * grid's [sharpness] (the variance of the Laplacian of Y near the inner grid lines, as the camera adapter computes it) and
 * [seams]: how many of the grid's 12 inner cell edges (0–12) show a dark seam between their two cells, the gap between
 * stickers or pieces that a cube face has and a floor, a wall or clothing does not. No pixels cross into the domain.
 */
data class FrameStats(
    val cells: List<CellReading?>,
    val meanLuma: Double,
    val glareFraction: Double,
    val sharpness: Double,
    val seams: Int,
)

/**
 * Frame quality for the Guided Scan, in precedence order LOW_LIGHT → GLARE → BLURRED → MISALIGNED → ACCEPTABLE.
 * Aligned means a plausible cube face fills the grid: nine readable cells, at least [MIN_PLAIN_CELLS] of them plain, a
 * plain center, and a seam on a majority ([MIN_SEAMS]) of the inner cell edges — a steady, sharp, bright background is
 * not. [assess] gates auto-capture; [assessShutter] gates the manual shutter, which skips the cube-face check (PO
 * 2026-11-16) so a face that is not recognized can still be captured. Thresholds are provisional: they are tuned on the
 * reference devices in device session B.
 */
object FrameQuality {
    /** Mean grid luma (0–255) below this is too dark. Provisional, tuned in device session B. */
    const val MIN_MEAN_LUMA = 40.0

    /** Luma at or above this is a glare pixel. Provisional, tuned in device session B. */
    const val GLARE_LUMA = 250

    /** A specular spot (at [GLARE_LUMA]) covering at least this fraction of one colored cell is glare. Provisional, tuned in device session B. */
    const val MAX_GLARE_FRACTION = 0.15

    /**
     * Sharpness (variance of the Laplacian of Y near the inner grid lines) below this is blurred. Provisional, tuned in
     * device session B.
     */
    const val MIN_SHARPNESS = 60.0

    /** At least this many of the nine cells must be plain for the grid to sit on one face. Provisional (session B). */
    const val MIN_PLAIN_CELLS = 7

    /**
     * At least this many of the 12 inner cell edges (a majority) must show a seam for the grid to hold a cube face, not
     * background (device check 2026-11-16: floor and clothing were captured; a stickerless face showing only some of its
     * thin seams was not). Provisional, tuned in device session B.
     */
    const val MIN_SEAMS = 7

    /** Inner cell edges of the 3×3 grid: 6 between columns, 6 between rows. */
    const val INNER_EDGES = 12

    const val CELLS = 9

    /** The manual shutter's quality: light, glare, sharpness and nine readable cells, without the cube-face check. */
    fun assessShutter(stats: FrameStats): CaptureQuality =
        when {
            stats.meanLuma < MIN_MEAN_LUMA -> CaptureQuality.LOW_LIGHT
            stats.glareFraction >= MAX_GLARE_FRACTION -> CaptureQuality.GLARE
            stats.sharpness < MIN_SHARPNESS -> CaptureQuality.BLURRED
            stats.cells.size != CELLS || stats.cells.any { it == null } -> CaptureQuality.MISALIGNED
            else -> CaptureQuality.ACCEPTABLE
        }

    /** The auto-capture quality: [assessShutter], then the cube-face check. */
    fun assess(stats: FrameStats): CaptureQuality =
        when {
            assessShutter(stats) != CaptureQuality.ACCEPTABLE -> assessShutter(stats)

            stats.cells.count { it?.plain == true } < MIN_PLAIN_CELLS -> CaptureQuality.MISALIGNED

            // A cube face: the center is one plain sticker and the nine cells are parted by seams.
            stats.cells[FaceSample.CENTER_INDEX]?.plain != true -> CaptureQuality.MISALIGNED

            stats.seams < MIN_SEAMS -> CaptureQuality.MISALIGNED

            else -> CaptureQuality.ACCEPTABLE
        }
}

/** Categorical coaching for the Learner; never an error. */
enum class Coaching { GLARE, BLURRED, LOW_LIGHT, MISALIGNED, WRONG_FACE }

/** What the viewfinder must do after a controller call. */
sealed interface GuidedScanEffect {
    /** Re-meter AF/AE/AWB on the grid. */
    data object Remeter : GuidedScanEffect

    /** A face was captured and stored. */
    data class Capture(
        val sample: FaceSample,
    ) : GuidedScanEffect

    /** All six faces are captured, in plan order. */
    data class Done(
        val samples: List<FaceSample>,
    ) : GuidedScanEffect
}

/**
 * The Guided Scan decisions (Story 10.2), pure and single-threaded, with time injected as `nowMs`.
 *
 * Alignment is an ACCEPTABLE frame. The first ACCEPTABLE frame after a non-ACCEPTABLE one (or at a new step) emits
 * [GuidedScanEffect.Remeter]; after [SETTLE_MS] the first frame becomes the steadiness reference, and once every cell
 * has stayed within [STEADY_DELTA_E] of it for [STEADY_MS] the face is captured automatically. A cell that moves resets
 * the reference and the timer. [onShutter] captures the latest frame at once when [FrameQuality.assessShutter]
 * accepts it.
 *
 * Wrong face (PO 2026-11-15): only a repeated face is caught live. A capture whose center is within
 * [SAME_CENTER_DELTA_E] of an already captured center is rejected with [Coaching.WRONG_FACE] and not stored; any other
 * wrong face or wrong side on top is stored under the step's labels and left to Story 10.3 validation. The second such
 * rejection at one step sets [canUseAnyway], and [onUseAnyway] stores the last rejected frame. An ACCEPTABLE frame with
 * any cell at least [FACE_CHANGE_DELTA_E] from the last rejected frame (a different face) clears the rejections, the
 * override and the WRONG_FACE coaching; a transient non-ACCEPTABLE frame does not.
 *
 * Right after a capture the face just captured is still in view: frames are ignored (no capture, no coaching) until the
 * frame changes — a non-ACCEPTABLE frame, or any cell at least [FACE_CHANGE_DELTA_E] from the captured values — and the
 * change counts as newly found alignment.
 */
class GuidedScanController(
    private val plan: List<ScanStep> = GuidedScanPlan.steps,
) {
    private data class Frame(
        val stats: FrameStats,
        val orientationDegrees: Int,
        val quality: CaptureQuality,
        val shutterQuality: CaptureQuality,
    )

    private val stored = mutableListOf<FaceSample>()
    private var latest: Frame? = null
    private var aligned = false
    private var remeteredAt = 0L
    private var reference: List<Lab>? = null
    private var steadySince = 0L
    private var justCaptured: List<Lab>? = null
    private var rejections = 0
    private var lastRejected: Frame? = null

    /** Index into the plan of the face to show next; equals the plan size when done. */
    val stepIndex: Int get() = stored.size

    /** The step to show next, or null when every face is captured. */
    val step: ScanStep? get() = plan.getOrNull(stepIndex)

    val captured: List<FaceSample> get() = stored.toList()

    val done: Boolean get() = stored.size == plan.size

    /** The current coaching, or null for none. */
    var coaching: Coaching? = null
        private set

    /** True after a repeated-face rejection happened twice at the current step: offer *Use this face anyway*. */
    val canUseAnyway: Boolean get() = rejections >= REJECTIONS_BEFORE_OVERRIDE && lastRejected != null

    /** Feeds one analysis frame, whose image needs [orientationDegrees] clockwise to be upright. */
    fun onFrame(
        stats: FrameStats,
        orientationDegrees: Int,
        nowMs: Long,
    ): List<GuidedScanEffect> {
        if (done) return emptyList()
        val quality = FrameQuality.assess(stats)
        val frame = Frame(stats, orientationDegrees, quality, FrameQuality.assessShutter(stats))
        latest = frame
        if (quality != CaptureQuality.ACCEPTABLE) {
            justCaptured = null
            aligned = false
            reference = null
            coaching = coachingFor(quality)
            return emptyList()
        }
        val values = stats.cells.map { it!!.value }
        lastRejected?.let { rejected ->
            if (movedFrom(values, rejected.stats.cells.map { it!!.value })) clearRejection()
        }
        val held = justCaptured
        if (held != null) {
            // Still the face just captured: ignored, no capture and no new coaching.
            if (!movedFrom(values, held)) return emptyList()
            justCaptured = null
            aligned = false
        }
        if (!aligned) {
            aligned = true
            coaching = null
            return listOf(remeter(nowMs))
        }
        if (nowMs < remeteredAt + SETTLE_MS) return emptyList()
        val ref = reference
        if (ref == null || values.zip(ref).any { (now, then) -> StickerClassifier.deltaE(now, then) >= STEADY_DELTA_E }) {
            reference = values
            steadySince = nowMs
            return emptyList()
        }
        if (nowMs - steadySince < STEADY_MS) return emptyList()
        return capture(frame, nowMs)
    }

    /**
     * The manual shutter: captures the latest frame now when it is bright, glare-free, sharp and has nine readable cells,
     * even if it is not recognized as a cube face (PO 2026-11-16); otherwise only coaches. While the face just captured
     * is still held it does nothing (no capture, no coaching, no rejection count).
     */
    fun onShutter(nowMs: Long): List<GuidedScanEffect> {
        if (done || justCaptured != null) return emptyList()
        val frame = latest
        if (frame == null || frame.shutterQuality != CaptureQuality.ACCEPTABLE) {
            coaching = frame?.let { coachingFor(it.shutterQuality) } ?: Coaching.MISALIGNED
            return emptyList()
        }
        return capture(frame, nowMs)
    }

    /** *Use this face anyway*: stores the last rejected frame as this step's face, when [canUseAnyway]. */
    fun onUseAnyway(nowMs: Long): List<GuidedScanEffect> {
        if (done || !canUseAnyway) return emptyList()
        return store(requireNotNull(lastRejected), nowMs)
    }

    private fun capture(
        frame: Frame,
        nowMs: Long,
    ): List<GuidedScanEffect> {
        val center = frame.stats.cells[FaceSample.CENTER_INDEX]!!.value
        val repeated =
            stored.any { previous ->
                previous.center?.let { StickerClassifier.deltaE(it, center) < SAME_CENTER_DELTA_E } == true
            }
        if (!repeated) return store(frame, nowMs)
        coaching = Coaching.WRONG_FACE
        rejections++
        lastRejected = frame
        // Restart the steady timer so the same face is not re-tried on every frame.
        reference = frame.stats.cells.map { it!!.value }
        steadySince = nowMs
        return emptyList()
    }

    private fun store(
        frame: Frame,
        nowMs: Long,
    ): List<GuidedScanEffect> {
        val step = plan[stepIndex]
        val cells = frame.stats.cells.map { it!! }
        val sample =
            FaceSample(
                face = step.face,
                orientationDegrees = frame.orientationDegrees,
                stickers = cells.map { it.value },
                quality = CaptureQuality.ACCEPTABLE,
                centerColor = step.centerColor,
                upNeighborColor = step.upNeighborColor,
                centerPlain = cells[FaceSample.CENTER_INDEX].plain,
            )
        stored += sample
        coaching = null
        clearRejection()
        justCaptured = sample.stickers
        val next = if (done) GuidedScanEffect.Done(captured) else remeter(nowMs)
        return listOf(GuidedScanEffect.Capture(sample), next)
    }

    /** A different face is shown: the repeated-face rejections, the override and WRONG_FACE coaching no longer apply. */
    private fun clearRejection() {
        rejections = 0
        lastRejected = null
        if (coaching == Coaching.WRONG_FACE) coaching = null
    }

    private fun movedFrom(
        values: List<Lab>,
        previous: List<Lab>,
    ): Boolean = values.zip(previous).any { (now, then) -> StickerClassifier.deltaE(now, then) >= FACE_CHANGE_DELTA_E }

    private fun remeter(nowMs: Long): GuidedScanEffect {
        remeteredAt = nowMs
        reference = null
        return GuidedScanEffect.Remeter
    }

    companion object {
        /** Aligned and steady this long before an automatic capture. */
        const val STEADY_MS = 600L

        /** After a re-meter, AF/AE/AWB converge this long before steadiness is measured. Provisional (session B). */
        const val SETTLE_MS = 300L

        /** Every cell must move less than this (CIE76) from the reference to count as steady. */
        const val STEADY_DELTA_E = 5.0

        /**
         * A center this close (CIE76) to an already captured center is the same face again. Provisional, tuned in
         * device session B.
         */
        const val SAME_CENTER_DELTA_E = 10.0

        /**
         * A cell this far (CIE76) from the face just captured means the cube moved on (the recorder's threshold).
         * Provisional, tuned in device session B.
         */
        const val FACE_CHANGE_DELTA_E = 15.0

        /** Repeated-face rejections at one step before *Use this face anyway* is offered. */
        const val REJECTIONS_BEFORE_OVERRIDE = 2

        fun coachingFor(quality: CaptureQuality): Coaching? =
            when (quality) {
                CaptureQuality.ACCEPTABLE -> null
                CaptureQuality.GLARE -> Coaching.GLARE
                CaptureQuality.BLURRED -> Coaching.BLURRED
                CaptureQuality.LOW_LIGHT -> Coaching.LOW_LIGHT
                CaptureQuality.MISALIGNED, CaptureQuality.INCOMPLETE -> Coaching.MISALIGNED
            }
    }
}
