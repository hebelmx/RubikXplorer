package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face

/**
 * Debug diagnostics of Quick Scan's reads (Story 10.21 device session): what each corner face's centre read and why it
 * gave no confident read, which reference the reads used and why, every sticker's two reads with their margins, the
 * few-confident wait's per-face counts, the hand-over's kept and grid faces, and how a hand-over (face-by-face) capture
 * reads. Debug logging only: nothing decides on these values, and they carry colour names, counts and ΔE margins, never
 * pixels or Cube States (AD-9). The decisions themselves stay in [QuickScanClassification] and [QuickScanController].
 */
object QuickScanDiagnosis {
    /** Why a face's centre gave no CONFIDENT read; null on a [FaceDiagnosis] whose centre read CONFIDENT. */
    enum class CentreIssue(
        val label: String,
    ) {
        /** The centre cell is not plain (its histogram mode holds under 60%): a logo, a glare hot spot or a mixed cell. */
        GUARDED("guarded"),

        /** The centre's Lab is out of range (not finite, or L* outside 0..100). */
        INVALID("invalid"),

        /** The centre is near-black: L* at or below [FrameQuality.DARK_CELL_MAX_L]. */
        DARK("dark"),

        /** Fewer than two reference centres to read against, so nothing on the face could be read. */
        NO_REFERENCE("no-reference"),

        /** Read, but not CONFIDENT through the agreement gate. */
        UNCERTAIN("uncertain"),
    }

    /** The reference the face's first (self-calibration) read competed over. */
    enum class Reference(
        val label: String,
    ) {
        /** A saved calibration that matched (torch tag and centres): it fills the self-calibration and is the second read. */
        SAVED("saved-match"),

        /** Six centres from the captured faces themselves (a single guarded one derived from the other stickers). */
        SELF("self"),

        /** Only the captured faces' own trusted (plain) centres: some colours have no centre yet. */
        PARTIAL("partial"),

        /** Fewer than two trusted centres: no sticker can be read. */
        NONE("none"),
    }

    /** Why the hand-over sent a face to the Guided grid ([QuickScanClassification.decide]'s first failing rule). */
    enum class GridReason(
        val label: String,
    ) {
        MISSING("missing"),
        QUALITY("quality"),
        UNCALIBRATED("uncalibrated"),
        HARD_DISAGREEMENT("hard-disagreement"),
        CENTRE_UNCERTAIN("centre-uncertain"),
        UNCERTAIN("uncertain"),

        /** Kept by the gate, but involved in an ambiguous or no-fit assembly ([QuickScanClassification.involvedFaces]). */
        REPROMPT("reprompt"),
    }

    /**
     * One sticker: the gated [color] (the first read's colour; null when unreadable) and whether the gate made it
     * [confident]; whether the hand-over decision [judged] it (a guarded centre is not); the first read's [firstMargin]
     * (ΔE to the second-nearest centre) and the second read's [secondColor] and [secondMargin].
     */
    data class StickerDiagnosis(
        val color: Color?,
        val confident: Boolean,
        val judged: Boolean,
        val firstMargin: Double?,
        val secondColor: Color?,
        val secondMargin: Double?,
    )

    /**
     * One captured corner face as [QuickScanClassification] reads it. [centre] is the centre sticker's read and
     * [centreIssue] why it is not CONFIDENT (the first that applies). [reference] is what the first read used, over
     * [referenceColors]; why: the saved [calibration]'s match (null: none saved), the [guarded] colours whose own centre
     * was not used and the [unresolved] colours with no centre at all. [counted] is what the few-confident wait sums
     * ([QuickScanClassification.confidentJudged]: the CONFIDENT judged stickers, 0 with no reference), [confident] the
     * CONFIDENT judged stickers the capture line counts. [gridReason] is null for a face kept.
     */
    data class FaceDiagnosis(
        val face: Face,
        val quality: CaptureQuality?,
        val centrePlain: Boolean?,
        val centre: StickerDiagnosis?,
        val centreIssue: CentreIssue?,
        val reference: Reference,
        val referenceColors: Set<Color>,
        val calibration: CalibrationMatch?,
        val guarded: Set<Color>,
        val unresolved: Set<Color>,
        val stickers: List<StickerDiagnosis>,
        val counted: Int,
        val confident: Int,
        val gridReason: GridReason?,
        val blanks: Set<Int>,
    )

    /**
     * The few-confident wait's count of corner [hold]: CONFIDENT judged stickers [perFace] (a guarded centre not counted)
     * of each face in view that has nine cells and a colour reference, on the frame of the smoothing window with the most
     * ([QuickScanController.CONFIDENT_WINDOW_MS]); [amount] is exactly the number compared with [need].
     */
    data class ConfidentCount(
        val hold: Int,
        val perFace: Map<Face, Int>,
        val need: Int,
    ) {
        val amount: Int get() = perFace.values.sum()
    }

    /** What started a hand-over: the second corner's capture, or *Scan face by face*. */
    enum class HandOverTrigger(
        val label: String,
    ) {
        AUTO("auto"),
        FACE_BY_FACE("faceByFace"),
    }

    /**
     * One hand-over: the faces [kept] from the corners (with the judged positions each leaves [blanks]), the faces sent
     * to the [grid] and why, whether it went straight to review ([done]) and the scan's calibration match (null: none
     * saved).
     */
    data class HandOverReport(
        val trigger: HandOverTrigger,
        val kept: List<Face>,
        val grid: Map<Face, GridReason>,
        val blanks: Map<Face, Set<Int>>,
        val done: Boolean,
        val calibration: CalibrationMatch?,
    )

    /**
     * A hand-over (face-by-face) capture read against the self-calibration over the scan's faces (as review reads it):
     * per sticker in frame order the colour and confidence, null where unreadable.
     */
    data class GuidedFaceRead(
        val face: Face,
        val orientationDegrees: Int,
        val quality: CaptureQuality,
        val reference: Reference,
        val readings: List<StickerReading?>,
    )

    /** [sample] read against the self-calibration over [all] (the scan's faces, [sample] among them) and [saved]. */
    fun guidedRead(
        sample: FaceSample,
        all: List<FaceSample>,
        saved: CalibrationCenters?,
    ): GuidedFaceRead {
        val usable = saved?.takeIf { StickerClassifier.isValid(it) }
        val self = SelfCalibration.from(all, usable)
        val centers =
            when (self) {
                is SelfCalibration.Calibrated -> self.calibration.centers
                is SelfCalibration.NeedsCalibration -> SelfCalibration.trustedCenters(all)
            }
        val reference = reference(usable != null, self, centers)
        val readings =
            sample.stickers.map { sticker ->
                if (centers.size >= 2 && sticker.isValid() && centers.values.all { it.isValid() }) {
                    StickerClassifier.read(sticker, centers)
                } else {
                    null
                }
            }
        return GuidedFaceRead(sample.face, sample.orientationDegrees, sample.quality, reference, readings)
    }

    /** The reference a read used: the matched saved calibration, six own centres, a partial set or none. */
    internal fun reference(
        matched: Boolean,
        self: SelfCalibration,
        centers: Map<Color, Lab>,
    ): Reference =
        when {
            matched -> Reference.SAVED
            self is SelfCalibration.Calibrated -> Reference.SELF
            centers.size >= 2 -> Reference.PARTIAL
            else -> Reference.NONE
        }
}
