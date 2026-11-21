package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import kotlin.math.sqrt

/**
 * One Guided Scan prompt: show the [centerColor] center to the camera, with the [upNeighborColor] center on top when the
 * prompt names a top (a re-prompt, Story 10.14); null names none. [face] is the face whose home color is [centerColor].
 */
data class ScanStep(
    val centerColor: Color,
    val upNeighborColor: Color? = null,
) {
    val face: Face get() = Face.entries.first { Color.home(it) == centerColor }
}

/**
 * The fixed Guided 6-face Scan order (Stories 10.2, 10.14): green, red, blue, orange, white, yellow, each prompt naming
 * only the center; `ScanAssembler` works out each face's turn. A face it cannot place is re-prompted with [reprompt]:
 * the 10.2 step that shows the face exactly as the Kociemba net draws it (side faces with white on top, white with blue
 * on top, yellow with green on top).
 */
object GuidedScanPlan {
    val steps: List<ScanStep> =
        listOf(Color.GREEN, Color.RED, Color.BLUE, Color.ORANGE, Color.WHITE, Color.YELLOW).map { ScanStep(it) }

    /** The re-prompt step for [face]: its center with the top that needs no turn. */
    fun reprompt(face: Face): ScanStep =
        ScanStep(
            Color.home(face),
            when (face) {
                Face.U -> Color.BLUE
                Face.D -> Color.GREEN
                else -> Color.WHITE
            },
        )
}

/**
 * What the camera adapter measured on one analysis frame, on the face it located inside the drawn grid (Story 10.14,
 * PO 2026-11-16: a 3×3 lattice from about 70 % to 100 % of the grid's size, offset up to about half a cell): the nine
 * cell readings in pixel row-major order (null when a cell had no reading), the mean luma (Y, 0–255), [glareFraction]:
 * the largest share of one cell's pixels in a specular spot (clipped, Y ≥ 250, near-neutral pixels inside a saturated
 * sticker color; a white sticker, also one mixed with black seam or body, is never glare), the face's [sharpness] (the
 * variance of the Laplacian of Y near its inner lines, or near its outer edge when that is sharper, as the camera adapter
 * computes it) and [seams]: how many of its 12 inner cell edges (0–12) part their two cells, by a dark seam (the gap
 * between stickers or pieces that a cube face has and a floor, a wall or clothing does not) or by a color step of at
 * least [FrameQuality.COLOR_STEP_DELTA_E] between the two cells' readings when both are sticker evidence
 * ([FrameQuality.isStickerEvidence]; a gapless stickerless cube, PO 2026-11-16).
 * [faceEdges] is how many of the face's four sides (0–4) contrast with what lies just outside them. [framing] is set
 * when no face is within the tolerance but one is clearly present outside it: what the Learner should do, as they see
 * the preview. No pixels cross into the domain.
 */
data class FrameStats(
    val cells: List<CellReading?>,
    val meanLuma: Double,
    val glareFraction: Double,
    val sharpness: Double,
    val seams: Int,
    val faceEdges: Int = 0,
    val framing: Framing? = null,
)

/** How the Learner should move a cube face that is clearly present but outside the tolerance, as they see the preview. */
enum class Framing { CLOSER, LEFT, RIGHT, UP, DOWN }

/**
 * Frame quality for the Guided Scan, in precedence order LOW_LIGHT → GLARE → BLURRED → MISALIGNED → ACCEPTABLE.
 * Aligned means a plausible cube face fills the grid: nine readable cells, none of them near-black (dark background or
 * black body, [DARK_CELL_MAX_L]), at least [MIN_PLAIN_OUTER_CELLS] of the eight
 * outer cells plain (the center may carry a logo, PO 2026-11-16), a seam on a majority ([MIN_SEAMS]) of the inner cell
 * edges, and sticker evidence: at least [MIN_STICKER_CELLS] cells a saturated sticker color (C*ab ≥
 * [STICKER_MIN_CHROMA]) or a bright white one (L* ≥ [WHITE_STICKER_MIN_L]) — a steady, sharp, bright background or a
 * dull gray scene with faint seams is not. A face with too few seams is still aligned when it is outlined (PO
 * 2026-11-16, a solved face of a gapless stickerless cube): all [FACE_EDGES] sides contrast with their surroundings, all
 * nine cells are plain sticker evidence, and every inner edge without a seam joins two cells of one color (within
 * [COLOR_STEP_DELTA_E]); a plain wall or floor has no outer edge, a gray scene no sticker evidence. [assess] gates auto-capture; [assessShutter] gates the manual shutter, which skips the cube-face check (PO
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

    /**
     * At least this many of the eight outer cells must be plain for the grid to sit on one face; the center is not
     * judged, since most branded cubes print a logo on it (PO 2026-11-16). Provisional (session B).
     */
    const val MIN_PLAIN_OUTER_CELLS = 6

    /**
     * At least this many of the nine cells must show sticker evidence for auto-capture: a dull, low-chroma gray scene
     * must never auto-capture (device check 2026-11-16, PO). Provisional (session B).
     */
    const val MIN_STICKER_CELLS = 3

    /** A cell at least this chromatic (CIELAB C*ab) is a saturated sticker color. Provisional (session B). */
    const val STICKER_MIN_CHROMA = 25.0

    /** A cell at least this light (CIELAB L*) is a white sticker's evidence. Provisional (session B). */
    const val WHITE_STICKER_MIN_L = 75.0

    /**
     * At least this many of the 12 inner cell edges (a majority) must show a seam for the grid to hold a cube face, not
     * background (device check 2026-11-16: floor and clothing were captured; a stickerless face showing only some of its
     * thin seams was not). Provisional, tuned in device session B.
     */
    const val MIN_SEAMS = 7

    /**
     * Two neighboring cells whose readings differ by at least this CIE76 ΔE are parted by a color step, which counts as
     * a seam; closer ones are one color. Provisional (session B).
     */
    const val COLOR_STEP_DELTA_E = 15.0

    /**
     * A cell at most this light (CIELAB L*) and [DARK_CELL_MAX_CHROMA] chromatic is dark background or black cube body:
     * no cube face has a black sticker, so the grid is off the face (device session B2: a third of the cells read L* < 25
     * when the cube sat off the grid). Provisional (session B).
     */
    const val DARK_CELL_MAX_L = 25.0
    const val DARK_CELL_MAX_CHROMA = 15.0

    /** An outlined face contrasts with its surroundings on all four sides. */
    const val FACE_EDGES = 4

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
    fun assess(stats: FrameStats): CaptureQuality = inspect(stats).quality

    /**
     * [assess] broken down per check: the first check that fails, in precedence order, and every measured value (no
     * pixels), so a debug build can tell which check holds back auto-capture.
     */
    fun inspect(stats: FrameStats): FrameInspection {
        val chroma = stats.cells.map { cell -> cell?.value?.let { sqrt(it.a * it.a + it.b * it.b) } }
        val plainOuter = stats.cells.filterIndexed { index, cell -> index != FaceSample.CENTER_INDEX && cell?.plain == true }.size
        val stickerCells = stats.cells.indices.count { chroma[it] != null && isStickerEvidence(chroma[it]!!, stats.cells[it]!!.value.l) }
        val outlinedOnly = stats.seams < MIN_SEAMS && outlined(stats, chroma)
        val failed =
            when {
                stats.meanLuma < MIN_MEAN_LUMA -> FrameCheck.LIGHT

                stats.glareFraction >= MAX_GLARE_FRACTION -> FrameCheck.GLARE

                stats.sharpness < MIN_SHARPNESS -> FrameCheck.SHARPNESS

                stats.cells.size != CELLS || stats.cells.any { it == null } -> FrameCheck.READABLE_CELLS

                stats.cells.indices.any {
                    stats.cells[it]!!.value.l <= DARK_CELL_MAX_L && chroma[it]!! <= DARK_CELL_MAX_CHROMA
                } -> FrameCheck.DARK_CELLS

                // A cube face: its outer cells are plain stickers (the center may carry a logo) parted by seams.
                plainOuter < MIN_PLAIN_OUTER_CELLS -> FrameCheck.PLAIN_OUTER_CELLS

                stats.seams < MIN_SEAMS && !outlinedOnly -> FrameCheck.SEAMS

                stickerCells < MIN_STICKER_CELLS -> FrameCheck.STICKER_CELLS

                else -> null
            }
        return FrameInspection(
            failed = failed,
            readableCells = stats.cells.count { it != null },
            plainOuterCells = plainOuter,
            seams = stats.seams,
            faceEdges = stats.faceEdges,
            stickerCells = stickerCells,
            chroma = chroma,
            lightness = stats.cells.map { it?.value?.l },
            glareFraction = stats.glareFraction,
            sharpness = stats.sharpness,
            meanLuma = stats.meanLuma,
            outlinedOnly = outlinedOnly,
        )
    }

    /** An outlined face (see the class KDoc), given its cells' [chroma]. */
    private fun outlined(
        stats: FrameStats,
        chroma: List<Double?>,
    ): Boolean {
        if (stats.cells.size != CELLS || stats.faceEdges < FACE_EDGES || stats.cells.any { it == null || !it.plain }) return false
        if (stats.cells.indices.any { !isStickerEvidence(chroma[it]!!, stats.cells[it]!!.value.l) }) return false
        val values = stats.cells.map { it!!.value }
        val pairs =
            (0..2).flatMap { k ->
                (1..2).flatMap { line ->
                    listOf(
                        k * 3 + line - 1 to k * 3 + line,
                        (line - 1) * 3 + k to line * 3 + k,
                    )
                }
            }
        val joins = pairs.count { (first, second) -> StickerClassifier.deltaE(values[first], values[second]) < COLOR_STEP_DELTA_E }
        return stats.seams + joins >= INNER_EDGES
    }

    /**
     * Whether [reading] is sticker evidence: a saturated sticker color or a bright white sticker, never a dull gray, a
     * black body or a dark background. The camera adapter counts a color step as a seam only between two such cells.
     */
    fun isStickerEvidence(reading: CellReading): Boolean =
        isStickerEvidence(sqrt(reading.value.a * reading.value.a + reading.value.b * reading.value.b), reading.value.l)

    /** A saturated sticker color, or a bright white sticker; never a dull gray. */
    private fun isStickerEvidence(
        chroma: Double,
        lightness: Double,
    ): Boolean = chroma >= STICKER_MIN_CHROMA || lightness >= WHITE_STICKER_MIN_L
}

/** The [FrameQuality] checks, in precedence order. */
enum class FrameCheck(
    val quality: CaptureQuality,
) {
    LIGHT(CaptureQuality.LOW_LIGHT),
    GLARE(CaptureQuality.GLARE),
    SHARPNESS(CaptureQuality.BLURRED),
    READABLE_CELLS(CaptureQuality.MISALIGNED),
    DARK_CELLS(CaptureQuality.MISALIGNED),
    PLAIN_OUTER_CELLS(CaptureQuality.MISALIGNED),
    SEAMS(CaptureQuality.MISALIGNED),
    STICKER_CELLS(CaptureQuality.MISALIGNED),
}

/**
 * One frame's [FrameQuality.assess] broken down: the first [failed] check (null when ACCEPTABLE) and the measured values
 * each check compares with its threshold. [chroma] (C*ab) and [lightness] (L*) are per cell in pixel row-major order,
 * null for an unreadable cell. [outlinedOnly] is true when the face has too few seams and passes the seam check only
 * as an outlined face (a seamless solid face): auto-capture then waits [GuidedScanController.OUTLINED_STEADY_MS].
 * Numbers only, never pixels; for debug diagnostics (device session B2) and the auto-capture hold.
 */
data class FrameInspection(
    val failed: FrameCheck?,
    val readableCells: Int,
    val plainOuterCells: Int,
    val seams: Int,
    val faceEdges: Int,
    val stickerCells: Int,
    val chroma: List<Double?>,
    val lightness: List<Double?>,
    val glareFraction: Double,
    val sharpness: Double,
    val meanLuma: Double,
    val outlinedOnly: Boolean = false,
) {
    val quality: CaptureQuality get() = failed?.quality ?: CaptureQuality.ACCEPTABLE
}

/**
 * Categorical coaching for the Learner; never an error. The MOVE_ values coach a face that is clearly present but outside
 * the framing tolerance (PO 2026-11-16), as the Learner sees the preview.
 */
enum class Coaching {
    GLARE,
    BLURRED,
    LOW_LIGHT,
    MISALIGNED,
    WRONG_FACE,
    MOVE_CLOSER,
    MOVE_LEFT,
    MOVE_RIGHT,
    MOVE_UP,
    MOVE_DOWN,
    PLAIN_LIGHT_SURFACE,
}

/** What the viewfinder must do after a controller call. */
sealed interface GuidedScanEffect {
    /** Re-meter AF/AE/AWB on the grid. */
    data object Remeter : GuidedScanEffect

    /** A face was captured and stored. */
    data class Capture(
        val sample: FaceSample,
    ) : GuidedScanEffect

    /** All six faces are captured (after the re-prompt round, if any), in plan order. */
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
 * the reference and the timer. A face accepted only as an outlined face ([FrameInspection.outlinedOnly]: no seams,
 * one solid color) must stay steady for [OUTLINED_STEADY_MS] instead. A MISALIGNED frame whose [FrameStats.framing] is set coaches that move instead of
 * [Coaching.MISALIGNED]. [onShutter] captures the latest frame at once when [FrameQuality.assessShutter]
 * accepts it.
 *
 * A face in the grid (nine readable cells) that stays BLURRED or LOW_LIGHT for [STUCK_REMETER_MS] emits
 * [GuidedScanEffect.Remeter] again, at most once per [STUCK_REMETER_MS] while it stays so (PO 2026-11-16: after the
 * cube moved nearer or farther, focus stayed wrong until the Learner pressed Capture).
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
 *
 * Re-prompt round (Story 10.14): after the last first-round capture, [reprompt] names the faces the assembler cannot
 * place (by default self-calibration, then `ScanAssembler.repromptFaces`; none when calibration is not possible). When it
 * names any, the controller asks for each in turn with [GuidedScanPlan.reprompt] (a named top) and each capture replaces
 * that face's earlier sample; [GuidedScanEffect.Done] follows the last of them. There is at most one such round. In it a
 * capture is a repeated face only against stored centers other than the prompted face's own.
 */
class GuidedScanController internal constructor(
    private val plan: List<ScanStep>,
    private val allowAdditionalReprompts: Boolean,
    private val reprompt: (List<FaceSample>) -> List<Face>,
    /** Faces read elsewhere (Quick Scan's corners) that a capture must not repeat, besides the faces stored here. */
    private val known: List<FaceSample> = emptyList(),
) {
    constructor(
        plan: List<ScanStep> = GuidedScanPlan.steps,
        reprompt: (List<FaceSample>) -> List<Face> = { defaultReprompt(it) },
    ) : this(plan, false, reprompt)

    private data class Frame(
        val stats: FrameStats,
        val orientationDegrees: Int,
        val quality: CaptureQuality,
        val shutterQuality: CaptureQuality,
    )

    private val stored = mutableListOf<FaceSample>()
    private var repromptSteps: List<ScanStep>? = null
    private var repromptsDone = 0
    private var finished = false
    private var latest: Frame? = null
    private var aligned = false
    private var remeteredAt = 0L
    private var reference: List<Lab>? = null
    private var steadySince = 0L
    private var justCaptured: List<Lab>? = null
    private var rejections = 0
    private var lastRejected: Frame? = null
    private var stuckSince: Long? = null

    /** How many faces the first round asks for: 6 for a Guided Scan, 1 or 2 for a Quick Check. */
    val planSize: Int get() = plan.size

    /** Index into the plan of the first-round face to show next; equals the plan size once the first round is over. */
    val stepIndex: Int get() = minOf(stored.size, planSize)

    /** True during the re-prompt round. */
    val repeating: Boolean get() = repromptSteps != null && !finished

    /** The 0-based position of the current re-prompt in the round, while [repeating]. */
    val repromptIndex: Int get() = repromptsDone

    /** How many faces the re-prompt round asks for (0 when there is none). */
    val repromptCount: Int get() = repromptSteps?.size ?: 0

    /** The step to show next, or null when the scan is done. */
    val step: ScanStep?
        get() =
            when {
                finished -> null
                else -> repromptSteps?.getOrNull(repromptsDone) ?: plan.getOrNull(stepIndex)
            }

    /** The faces captured so far, in plan order (a re-prompt capture replaces its face's sample). */
    val captured: List<FaceSample> get() = stored.toList()

    val done: Boolean get() = finished

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
        val inspection = FrameQuality.inspect(stats)
        val quality = inspection.quality
        val frame = Frame(stats, orientationDegrees, quality, FrameQuality.assessShutter(stats))
        latest = frame
        if (quality != CaptureQuality.ACCEPTABLE) {
            justCaptured = null
            aligned = false
            reference = null
            coaching = coachingFor(quality, stats.framing)
            return remeterIfStuck(stats, quality, nowMs)
        }
        stuckSince = null
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
        val hold = if (inspection.outlinedOnly) OUTLINED_STEADY_MS else STEADY_MS
        if (nowMs - steadySince < hold) return emptyList()
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
        val own = step?.face?.takeIf { repromptSteps != null }
        val repeated =
            stored.any { previous ->
                previous.face != own && previous.center?.let { StickerClassifier.deltaE(it, center) < SAME_CENTER_DELTA_E } == true
            } ||
                known.any { other ->
                    other.face != step?.face && stored.none { it.face == other.face } &&
                        other.center?.let { StickerClassifier.deltaE(it, center) < SAME_CENTER_DELTA_E } == true
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
        val current = requireNotNull(step)
        val cells = frame.stats.cells.map { it!! }
        val sample =
            FaceSample(
                face = current.face,
                orientationDegrees = frame.orientationDegrees,
                stickers = cells.map { it.value },
                quality = CaptureQuality.ACCEPTABLE,
                centerColor = current.centerColor,
                upNeighborColor = current.upNeighborColor,
                centerPlain = cells[FaceSample.CENTER_INDEX].plain,
            )
        val steps = repromptSteps
        if (steps == null) {
            stored += sample
            if (stored.size == plan.size) {
                val faces = reprompt(captured).filter { face -> allowAdditionalReprompts || stored.any { it.face == face } }.distinct()
                if (faces.isEmpty()) finished = true else repromptSteps = faces.map(GuidedScanPlan::reprompt)
            }
        } else {
            val index = stored.indexOfFirst { it.face == current.face }
            if (index >= 0) stored[index] = sample else stored += sample
            repromptsDone++
            if (repromptsDone == steps.size) finished = true
        }
        coaching = null
        clearRejection()
        justCaptured = sample.stickers
        val next = if (finished) GuidedScanEffect.Done(captured) else remeter(nowMs)
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

    /**
     * A face in the grid that stays blurred or too dark re-meters every [STUCK_REMETER_MS]: focus or exposure set for an
     * earlier distance is wrong now, and only an ACCEPTABLE frame would otherwise re-meter.
     */
    private fun remeterIfStuck(
        stats: FrameStats,
        quality: CaptureQuality,
        nowMs: Long,
    ): List<GuidedScanEffect> {
        val faceInGrid = stats.cells.size == FrameQuality.CELLS && stats.cells.none { it == null }
        if (!faceInGrid || (quality != CaptureQuality.BLURRED && quality != CaptureQuality.LOW_LIGHT)) {
            stuckSince = null
            return emptyList()
        }
        val since = stuckSince ?: nowMs.also { stuckSince = it }
        if (nowMs - since < STUCK_REMETER_MS) return emptyList()
        stuckSince = nowMs
        return listOf(remeter(nowMs))
    }

    private fun remeter(nowMs: Long): GuidedScanEffect {
        remeteredAt = nowMs
        reference = null
        return GuidedScanEffect.Remeter
    }

    companion object {
        /** Self-calibration, then the faces `ScanAssembler` cannot place; none when the scan needs calibration first. */
        fun defaultReprompt(
            samples: List<FaceSample>,
            saved: CalibrationCenters? = null,
        ): List<Face> =
            when (val calibration = SelfCalibration.from(samples, saved)) {
                is SelfCalibration.Calibrated -> ScanAssembler.repromptFaces(samples, calibration.calibration)
                is SelfCalibration.NeedsCalibration -> emptyList()
            }

        /** Aligned and steady this long before an automatic capture. */
        const val STEADY_MS = 600L

        /**
         * A face accepted only as an outlined face (no seams, one solid color: a solved face of a gapless stickerless
         * cube, but also a book cover or colored paper) must be aligned and steady this long before an automatic
         * capture, so a solid-color object rarely captures by accident (PO 2026-11-16). The manual shutter is unchanged.
         */
        const val OUTLINED_STEADY_MS = 1200L

        /** After a re-meter, AF/AE/AWB converge this long before steadiness is measured. Provisional (session B). */
        const val SETTLE_MS = 300L

        /**
         * A face in the grid that stays BLURRED or LOW_LIGHT this long re-meters, and again every this long while it
         * stays so (PO 2026-11-16). Provisional, tuned in device session B.
         */
        const val STUCK_REMETER_MS = 1000L

        /** Every cell must move less than this (CIE76) from the reference to count as steady. */
        const val STEADY_DELTA_E = 5.0

        /**
         * A center this close (CIE76) to an already captured center is the same face again (GH #158). Tuned on the 12
         * recorded live sets (`Fixtures/live`, studio light, Lenovo TB128XU and S24 FE): face centers of different
         * colors in one set are at least 24.8 apart (RED vs ORANGE, `sm-s721u1_stla_studio-a`), and a face center is at
         * least 23.9 from any other color's calibration center (RED vs ORANGE, `sm-s721u1_stla_studio-a/L`), so 12.0 is
         * about half the smallest cross-color distance while covering ~75 % of recorded same-color jitter (~70 % at
         * 10). CIEDE2000 was rejected: it shrinks WHITE vs BLUE to 9.1 on a GAN logo center. Warm and dim light stay a
         * Story 10.8 measurement; `RepeatedFaceFixtureTest` guards the margin.
         */
        const val SAME_CENTER_DELTA_E = 12.0

        /**
         * A cell this far (CIE76) from the face just captured means the cube moved on (the recorder's threshold).
         * Provisional, tuned in device session B.
         */
        const val FACE_CHANGE_DELTA_E = 15.0

        /** Repeated-face rejections at one step before *Use this face anyway* is offered. */
        const val REJECTIONS_BEFORE_OVERRIDE = 2

        /** The coaching for [quality]; a MISALIGNED frame with a face outside the tolerance coaches its [framing]. */
        fun coachingFor(
            quality: CaptureQuality,
            framing: Framing? = null,
        ): Coaching? =
            when {
                quality == CaptureQuality.MISALIGNED && framing != null -> {
                    when (framing) {
                        Framing.CLOSER -> Coaching.MOVE_CLOSER
                        Framing.LEFT -> Coaching.MOVE_LEFT
                        Framing.RIGHT -> Coaching.MOVE_RIGHT
                        Framing.UP -> Coaching.MOVE_UP
                        Framing.DOWN -> Coaching.MOVE_DOWN
                    }
                }

                else -> {
                    qualityCoaching(quality)
                }
            }

        private fun qualityCoaching(quality: CaptureQuality): Coaching? =
            when (quality) {
                CaptureQuality.ACCEPTABLE -> null
                CaptureQuality.GLARE -> Coaching.GLARE
                CaptureQuality.BLURRED -> Coaching.BLURRED
                CaptureQuality.LOW_LIGHT -> Coaching.LOW_LIGHT
                CaptureQuality.MISALIGNED, CaptureQuality.INCOMPLETE -> Coaching.MISALIGNED
            }
    }
}
