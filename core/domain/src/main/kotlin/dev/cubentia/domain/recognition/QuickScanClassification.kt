package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face

/**
 * Whether a saved calibration may be used for a Quick Scan face (Story 10.21, AD-9): only when its torch tag equals the
 * torch actually lit at the face's capture (a legacy row with no tag counts as torch-off) and every plain captured centre
 * (captured under that torch) reads CONFIDENT as its own colour against it. Otherwise Quick Scan self-calibrates as
 * when no calibration exists. Each such centre must also lie within [QuickScanClassification.MAX_CENTRE_DELTA_E] of the
 * saved centre of its colour (PO 2026-11-24), so another cube's or another light's calibration fails as a centre
 * mismatch. [label] is the debug word (`match`, `mismatch torch`, `mismatch centre`).
 */
sealed interface CalibrationMatch {
    val label: String

    data object Match : CalibrationMatch {
        override val label: String get() = "match"
    }

    data class Mismatch(
        val reason: Reason,
    ) : CalibrationMatch {
        // Debug words only (never Learner text): "mismatch" and the reason.
        override val label: String get() = listOf(MISMATCH, reason.label).joinToString(" ")
    }

    enum class Reason(
        val label: String,
    ) {
        TORCH("torch"),
        CENTRE("centre"),
    }

    private companion object {
        const val MISMATCH = "mismatch"
    }
}

/**
 * Quick-Scan-only sticker classification with an agreement gate (Story 10.19). The Guided 6-face Scan never uses it;
 * [SelfCalibration] and [StickerClassifier] keep their semantics and are only reused.
 *
 * Every sticker gets two reads, and it is CONFIDENT only when both are CONFIDENT and name the same color; otherwise it is
 * UNCERTAIN (its face goes to the Guided grid). The gate only ever lowers confidence, so a mismatched calibration ends
 * in uncertainty, never in a confident wrong sticker that the self-calibration read would not also give.
 * - The **self-calibration read** is the one Quick Scan used before: [SelfCalibration.from] over the captured faces
 *   (with the saved calibration filling guarded or missing colors), or, while some colors have no sample yet, the
 *   captured faces' own trusted centers.
 * - With a **saved calibration**, the second read is a per-face correction against it: the face's own center sticker
 *   is a known color, so the L* gain that maps it to the saved center of that color is applied to all of the face's
 *   stickers, which are then read against the saved centers by the 10 ΔE close-call rule.
 * - With **no saved calibration**, the second read uses the same centers after per-face light normalization: each
 *   center's L* is scaled by this face's light level over the light level of the face that center came from
 *   ([SideLight.level]).
 *
 * Story 10.21: the saved calibration takes part only for faces it [match]es (torch tag and centres); a face it does not
 * match is read exactly as with no saved calibration. The gate is per colour: a sticker is CONFIDENT when the
 * self-calibration read is CONFIDENT and the second read names the same colour with a margin of at least
 * [AGREEMENT_MARGIN] for that colour (fitted on the tuning split; a margin of [StickerClassifier.CLOSE_CALL_DELTA_E] is
 * the 10.19 rule, "both CONFIDENT"). With a matching calibration a face is kept with at most [MAX_KEPT_BLANKS] judged
 * sticker not CONFIDENT (left blank for review) and no hard disagreement (both reads CONFIDENT naming different
 * colours); otherwise all of its judged stickers must be CONFIDENT.
 */
object QuickScanClassification {
    /**
     * The margin (ΔE to the second-nearest centre) both reads need, per colour they name, for the gate to agree (Story
     * 10.21). Fitted on the 10.20 tuning split only by `QuickScanGateTuningTest` (docs/evidence/10-21/tuning-and-replay.md):
     * from the 10.19 rule upwards until no face the keep rule keeps on a plain tuning group, under any calibration, carries a
     * confidently wrong sticker. Orange needs more: a red read confidently as orange by both reads on a kept face
     * (`sm-s721u1_stla_bar-warm-2`, 21.3). The pastel tuning group is left to Story 10.27 (its orange read as red would
     * need Red at 44.1, so Red would never be CONFIDENT on any cube).
     */
    val AGREEMENT_MARGIN: Map<Color, Double> =
        mapOf(
            Color.WHITE to StickerClassifier.CLOSE_CALL_DELTA_E,
            Color.YELLOW to StickerClassifier.CLOSE_CALL_DELTA_E,
            Color.GREEN to StickerClassifier.CLOSE_CALL_DELTA_E,
            Color.BLUE to StickerClassifier.CLOSE_CALL_DELTA_E,
            Color.RED to StickerClassifier.CLOSE_CALL_DELTA_E,
            Color.ORANGE to 21.3,
        )

    /** The 10.19 gate: both reads CONFIDENT and the same colour. */
    internal val STRICT_MARGIN: Map<Color, Double> = Color.entries.associateWith { StickerClassifier.CLOSE_CALL_DELTA_E }

    /** Judged stickers a kept face may leave blank, with a matching calibration only (Story 10.21). */
    const val MAX_KEPT_BLANKS = 1

    /**
     * How far (ΔE) a plain captured centre may lie from the saved centre of its colour for [match] (Story 10.21, PO
     * 2026-11-24: another cube's or another light's calibration must fail the match). Fitted on the 10.20 tuning split only
     * by `QuickScanGateTuningTest` (docs/evidence/10-21/tuning-and-replay.md): per colour, the 0.1 step above the largest
     * distance of a centre to its own calibration among the tuning groups whose own calibration passes the centre read, so
     * no such same-cube match is lost. A colour with no such centre on the tuning split (White) takes the largest fitted one.
     */
    val MAX_CENTRE_DELTA_E: Map<Color, Double> =
        mapOf(
            Color.WHITE to 40.4,
            Color.YELLOW to 18.1,
            Color.GREEN to 40.4,
            Color.BLUE to 31.3,
            Color.RED to 12.3,
            Color.ORANGE to 14.8,
        )

    /**
     * Whether [saved] may be used with faces captured under [torchLit] whose samples are [captured]; null when there is
     * no saved calibration or it is not six valid centres. A guarded (logo) centre is not judged, but at least one plain
     * centre must be: with none to check (no sample, or every centre guarded) it is a centre mismatch. Each plain centre
     * must read CONFIDENT as its own colour and lie within [maxCentreDeltaE] of that colour's saved centre (null: the
     * read alone, as before the closeness check; only the tuning fit uses it).
     */
    fun match(
        saved: CalibrationCenters?,
        torchLit: Boolean,
        captured: List<FaceSample>,
        maxCentreDeltaE: Map<Color, Double>? = MAX_CENTRE_DELTA_E,
    ): CalibrationMatch? {
        if (saved == null || !StickerClassifier.isValid(saved)) return null
        if ((saved.torchOn == true) != torchLit) return CalibrationMatch.Mismatch(CalibrationMatch.Reason.TORCH)
        val plain = captured.filter { it.centerPlain != false }
        val centres =
            plain.isNotEmpty() &&
                plain.all { sample ->
                    val center = sample.center
                    val color = sample.centerColor ?: Color.home(sample.face)
                    center != null &&
                        center.isValid() &&
                        StickerClassifier.read(center, saved.centers) == StickerReading(color, StickerConfidence.CONFIDENT) &&
                        close(center, saved.centers[color], maxCentreDeltaE?.get(color))
                }
        return if (centres) CalibrationMatch.Match else CalibrationMatch.Mismatch(CalibrationMatch.Reason.CENTRE)
    }

    /** Whether [center] lies within [max] ΔE of the saved centre [target]; no limit when [max] is null. */
    private fun close(
        center: Lab,
        target: Lab?,
        max: Double?,
    ): Boolean = max == null || (target != null && StickerClassifier.deltaE(center, target) <= max)

    /**
     * [match] for [face]: against the torch lit at its capture ([captureTorch], absent: off) and the centres of the
     * [merged] samples captured under the same torch state.
     */
    fun matchFor(
        face: Face,
        merged: List<FaceSample>,
        saved: CalibrationCenters?,
        captureTorch: Map<Face, Boolean> = emptyMap(),
        maxCentreDeltaE: Map<Color, Double>? = MAX_CENTRE_DELTA_E,
    ): CalibrationMatch? {
        val lit = captureTorch[face] == true
        return match(saved, lit, merged.filter { (captureTorch[it.face] == true) == lit }, maxCentreDeltaE)
    }

    /**
     * The scan's match over every [merged] face: the first mismatch, else [CalibrationMatch.Match]; null without [saved].
     * No face at all is never a match: a centre mismatch.
     */
    fun matchAll(
        merged: List<FaceSample>,
        saved: CalibrationCenters?,
        captureTorch: Map<Face, Boolean> = emptyMap(),
        maxCentreDeltaE: Map<Color, Double>? = MAX_CENTRE_DELTA_E,
    ): CalibrationMatch? {
        if (saved == null || !StickerClassifier.isValid(saved)) return null
        if (merged.isEmpty()) return CalibrationMatch.Mismatch(CalibrationMatch.Reason.CENTRE)
        val matches = merged.map { matchFor(it.face, merged, saved, captureTorch, maxCentreDeltaE) }
        return matches.firstOrNull { it is CalibrationMatch.Mismatch } ?: CalibrationMatch.Match
    }

    /** [saved] when it matches [face] ([matchFor]), else null: the face is read as with no saved calibration. */
    private fun usable(
        face: Face,
        merged: List<FaceSample>,
        saved: CalibrationCenters?,
        captureTorch: Map<Face, Boolean>,
    ): CalibrationCenters? = saved.takeIf { matchFor(face, merged, saved, captureTorch) == CalibrationMatch.Match }

    /**
     * One face's Quick Scan decision: its gated [readings] (all nine, null where no read is possible), whether it is
     * [keep]t (not sent to the Guided grid), the judged positions left blank ([blanks], viewfinder order) and whether a
     * saved calibration [matched].
     */
    data class FaceDecision(
        val readings: List<StickerReading?>,
        val keep: Boolean,
        val blanks: Set<Int>,
        val matched: Boolean,
        val hardDisagreement: Boolean,
    )

    /** [sample]'s decision among [merged] ([FaceDecision]), with the gate's per-colour [margin]. */
    fun decide(
        sample: FaceSample,
        merged: List<FaceSample>,
        saved: CalibrationCenters?,
        captureTorch: Map<Face, Boolean> = emptyMap(),
    ): FaceDecision = decide(sample, merged, saved, captureTorch, AGREEMENT_MARGIN)

    internal fun decide(
        sample: FaceSample,
        merged: List<FaceSample>,
        saved: CalibrationCenters?,
        captureTorch: Map<Face, Boolean>,
        margin: Map<Color, Double>,
    ): FaceDecision {
        val matched = usable(sample.face, merged, saved, captureTorch)
        val reads = reads(sample, merged, matched, margin)
        val judged = sample.stickers.indices.filter { it != FaceSample.CENTER_INDEX || sample.centerPlain != false }
        val uncertain = judged.filter { reads.gated.getOrNull(it)?.confidence != StickerConfidence.CONFIDENT }
        // A plain centre not CONFIDENT is never the allowed blank (review cannot blank a centre): the face goes to the grid.
        val centreUncertain = FaceSample.CENTER_INDEX in uncertain
        val blanks = uncertain.filterTo(linkedSetOf()) { it != FaceSample.CENTER_INDEX }
        val hard = matched != null && judged.any { hard(reads.first.getOrNull(it), reads.second.getOrNull(it)) }
        val unresolved = reads.self is SelfCalibration.NeedsCalibration && Color.home(sample.face) in reads.self.colors
        val allowed = if (matched != null) MAX_KEPT_BLANKS else 0
        val keep =
            sample.quality == CaptureQuality.ACCEPTABLE &&
                sample.stickers.size == ScanAssembler.STICKERS &&
                !unresolved &&
                !hard &&
                !centreUncertain &&
                blanks.size <= allowed
        return FaceDecision(reads.gated, keep, blanks, matched != null, hard)
    }

    /** Both reads CONFIDENT and naming different colours: beyond the gate, so the face goes to the grid. */
    private fun hard(
        first: StickerReading?,
        second: StickerReading?,
    ): Boolean =
        first?.confidence == StickerConfidence.CONFIDENT &&
            second?.confidence == StickerConfidence.CONFIDENT &&
            first.color != second.color

    /** The self-calibration the Quick Scan path reads with, null while some colors have no sample. */
    fun calibration(
        merged: List<FaceSample>,
        saved: CalibrationCenters?,
    ): CalibrationCenters? = (SelfCalibration.from(merged, saved) as? SelfCalibration.Calibrated)?.calibration

    /**
     * One gated reading per sticker of [sample] (all nine), null where no read is possible; [captureTorch] is the torch lit
     * at each face's capture (absent: off), which decides whether [saved] matches.
     */
    fun read(
        sample: FaceSample,
        merged: List<FaceSample>,
        saved: CalibrationCenters?,
        captureTorch: Map<Face, Boolean> = emptyMap(),
    ): List<StickerReading?> = decide(sample, merged, saved, captureTorch).readings

    /** Both reads of every sticker of [sample] before the gate, with the second read's margin (the tuning data, Story 10.21). */
    internal data class StickerReads(
        val first: StickerReading?,
        val second: StickerReading?,
        val firstMargin: Double?,
        val secondMargin: Double?,
    )

    internal fun stickerReads(
        sample: FaceSample,
        merged: List<FaceSample>,
        saved: CalibrationCenters?,
        captureTorch: Map<Face, Boolean> = emptyMap(),
    ): List<StickerReads> {
        val reads = reads(sample, merged, usable(sample.face, merged, saved, captureTorch), STRICT_MARGIN)
        return reads.first.indices.map {
            StickerReads(reads.first[it], reads.second.getOrNull(it), reads.firstMargins.getOrNull(it), reads.margins.getOrNull(it))
        }
    }

    private class Reads(
        val self: SelfCalibration,
        val first: List<StickerReading?>,
        val second: List<StickerReading?>,
        val margins: List<Double?>,
        val firstMargins: List<Double?>,
        val source: SecondRead,
        val gated: List<StickerReading?>,
    )

    /** Both reads of [sample] with [saved] (already matched, or null) and the per-colour gate over [margin]. */
    private fun reads(
        sample: FaceSample,
        merged: List<FaceSample>,
        saved: CalibrationCenters?,
        margin: Map<Color, Double>,
    ): Reads {
        val self = SelfCalibration.from(merged, saved)
        val centers: Map<Color, Lab> =
            when (self) {
                is SelfCalibration.Calibrated -> self.calibration.centers
                is SelfCalibration.NeedsCalibration -> SelfCalibration.trustedCenters(merged)
            }
        val firstMeasured = sample.stickers.map { sticker -> nearest(sticker, centers) }
        val first = firstMeasured.map { it?.let { (color, m) -> reading(color, m) } }
        val calibrated = saved != null && StickerClassifier.isValid(saved)
        val (stickers, secondCenters) =
            if (calibrated) {
                corrected(sample, checkNotNull(saved)) to saved.centers
            } else {
                sample.stickers to normalized(sample, merged, centers)
            }
        val measured = stickers.map { sticker -> nearest(sticker, secondCenters) }
        val second = measured.map { it?.let { (color, m) -> reading(color, m) } }
        val margins = measured.map { it?.second }
        val gated = first.indices.map { gate(firstMeasured[it], measured.getOrNull(it), margin) }
        val source = if (calibrated) SecondRead.CALIBRATION else SecondRead.NORMALIZED
        return Reads(self, first, second, margins, firstMeasured.map { it?.second }, source, gated)
    }

    private fun reading(
        color: Color,
        margin: Double,
    ) = StickerReading(
        color,
        if (margin >=
            StickerClassifier.CLOSE_CALL_DELTA_E
        ) {
            StickerConfidence.CONFIDENT
        } else {
            StickerConfidence.UNCERTAIN
        },
    )

    /** The nearest of [centers] to [sticker] with its margin; null when no read is possible. */
    private fun nearest(
        sticker: Lab,
        centers: Map<Color, Lab>,
    ): Pair<Color, Double>? =
        if (centers.size >= 2 && sticker.isValid() && centers.values.all { it.isValid() }) {
            StickerClassifier.nearestWithMargin(sticker, centers)
        } else {
            null
        }

    /**
     * Faces among [faces] that the Guided grid must read: missing, or not kept by [decide] (not ACCEPTABLE, whose own
     * color has no usable center yet, a hard disagreement, or more judged stickers not CONFIDENT than the rule allows).
     */
    fun needGrid(
        merged: List<FaceSample>,
        faces: List<Face>,
        saved: CalibrationCenters?,
        captureTorch: Map<Face, Boolean> = emptyMap(),
    ): List<Face> {
        val byFace = merged.associateBy { it.face }
        return faces.filter { face -> byFace[face]?.let { decide(it, merged, saved, captureTorch).keep } != true }
    }

    /**
     * What [needGrid] decided for each of [faces], and why (debug diagnostics, Story 10.19): the gated center color, the
     * judged stickers' CONFIDENT and UNCERTAIN counts, and for a face sent to the grid the cause. [FaceReport.closeCalls]
     * counts judged stickers the self-calibration read already left UNCERTAIN (or could not read); [FaceReport.disagreements]
     * those it read CONFIDENT that the second read did not confirm; [FaceReport.correctionRejected] is true when a saved
     * calibration exists but the per-face correction could not use the face's own center (guarded, missing or invalid).
     */
    fun report(
        merged: List<FaceSample>,
        faces: List<Face>,
        saved: CalibrationCenters?,
        captureTorch: Map<Face, Boolean> = emptyMap(),
    ): List<FaceReport> {
        val grid = needGrid(merged, faces, saved, captureTorch).toSet()
        val byFace = merged.associateBy { it.face }
        return faces.map { face ->
            val sample = byFace[face] ?: return@map FaceReport(face, null, 0, 0, face in grid, null, false, 0, 0, false)
            val keep = { index: Int -> index != FaceSample.CENTER_INDEX || sample.centerPlain != false }
            val matched = usable(face, merged, saved, captureTorch)
            val reads = reads(sample, merged, matched, AGREEMENT_MARGIN)
            val self = reads.self
            val gated = reads.gated
            val first = reads.first
            val second = reads.second
            val secondRead = reads.source
            val judged = gated.filterIndexed { index, _ -> keep(index) }
            val firstJudged = first.filterIndexed { index, _ -> keep(index) }
            FaceReport(
                face = face,
                center = gated.getOrNull(FaceSample.CENTER_INDEX)?.color,
                confident = judged.count { it?.confidence == StickerConfidence.CONFIDENT },
                uncertain = judged.count { it?.confidence != StickerConfidence.CONFIDENT },
                grid = face in grid,
                quality = sample.quality,
                uncalibrated = self is SelfCalibration.NeedsCalibration && Color.home(face) in self.colors,
                closeCalls = firstJudged.count { it?.confidence != StickerConfidence.CONFIDENT },
                disagreements =
                    judged.zip(firstJudged).count { (g, f) ->
                        f?.confidence == StickerConfidence.CONFIDENT && g?.confidence != StickerConfidence.CONFIDENT
                    },
                correctionRejected = matched != null && corrected(sample, matched) === sample.stickers,
                self = first,
                second = second,
                secondRead = secondRead,
                calibration = matchFor(face, merged, saved, captureTorch),
                blanks = if (face in grid) 0 else judged.count { it?.confidence != StickerConfidence.CONFIDENT },
            )
        }
    }

    /**
     * One face's [report]: color names and counts only. [quality] is null for a face not captured. Story 10.23 adds, for
     * debug logging beside the shadow sticker reader only, the per-sticker [self] (self-calibration) and [second] reads of
     * all nine stickers before the gate, and which [secondRead] it was; both are empty for a face not captured. Nothing
     * decides on them. Story 10.21 adds the face's [calibration] match (null: no saved calibration) and, for a kept face,
     * the judged stickers it leaves [blanks] for review.
     */
    data class FaceReport(
        val face: Face,
        val center: Color?,
        val confident: Int,
        val uncertain: Int,
        val grid: Boolean,
        val quality: CaptureQuality?,
        val uncalibrated: Boolean,
        val closeCalls: Int,
        val disagreements: Int,
        val correctionRejected: Boolean,
        val self: List<StickerReading?> = emptyList(),
        val second: List<StickerReading?> = emptyList(),
        val secondRead: SecondRead? = null,
        val calibration: CalibrationMatch? = null,
        val blanks: Int = 0,
    )

    /** Which second read the gate compared with: the saved calibration (`cal` in the logs) or the normalized centers (`norm`). */
    enum class SecondRead { CALIBRATION, NORMALIZED }

    /**
     * The per-colour agreement gate (Story 10.21) over both reads as (nearest colour, margin): the sticker is CONFIDENT
     * only when both name the same colour and each margin is at least that colour's [margin]; otherwise it is UNCERTAIN
     * in the first read's colour. With every margin at [StickerClassifier.CLOSE_CALL_DELTA_E] this is the 10.19 gate.
     */
    internal fun gate(
        first: Pair<Color, Double>?,
        second: Pair<Color, Double>?,
        margin: Map<Color, Double> = AGREEMENT_MARGIN,
    ): StickerReading? {
        first ?: return null
        val (color, firstMargin) = first
        val needed = margin.getValue(color)
        val agree = second != null && color == second.first && firstMargin >= needed && second.second >= needed
        return StickerReading(color, if (agree) StickerConfidence.CONFIDENT else StickerConfidence.UNCERTAIN)
    }

    /**
     * Faces to re-prompt once every face passed [needGrid] (an ambiguous or no-fit assembly), by the same gated
     * classification: any face whose gated read is not all CONFIDENT is involved; otherwise every gated read equals the
     * self-calibration read (the gate keeps the first read's color), so [ScanAssembler.repromptFaces] over the
     * self-calibration sees exactly the gated colors. Faces in [kept] (kept by an earlier hand-over) are never asked
     * again: when every gated-uncertain face is kept, the assembly's own reprompt faces outside [kept] are asked instead,
     * so an ambiguous scan never ends Done without a reprompt. Empty while some colors have no center.
     */
    fun involvedFaces(
        merged: List<FaceSample>,
        saved: CalibrationCenters?,
        kept: Set<Face> = emptySet(),
        captureTorch: Map<Face, Boolean> = emptyMap(),
    ): List<Face> {
        val usable = saved.takeIf { matchAll(merged, it, captureTorch) == CalibrationMatch.Match }
        val calibration = calibration(merged, usable) ?: return emptyList()
        val uncertain = needGrid(merged, merged.map { it.face }, saved, captureTorch).filter { it !in kept }
        if (uncertain.isNotEmpty()) return uncertain
        return ScanAssembler.repromptFaces(merged, calibration, repromptPosed = true).filter { it !in kept }
    }

    /** The face's stickers with the L* gain that maps its own (plain) center onto the saved center of that color. */
    internal fun corrected(
        sample: FaceSample,
        saved: CalibrationCenters,
    ): List<Lab> {
        val own = sample.center
        val target = saved.centers[sample.centerColor ?: Color.home(sample.face)]
        if (own == null || target == null || sample.centerPlain == false || !own.isValid() || own.l <= 0.0) return sample.stickers
        val gain = target.l / own.l
        return sample.stickers.map { it.copy(l = (it.l * gain).coerceIn(0.0, 100.0)) }
    }

    /**
     * [centers] with each L* scaled by this face's light level over the level of the face the center came from. A
     * center's source face is the one captured sample whose [FaceSample.centerColor] is that color, when its center is
     * plain and the sample usable (as [SelfCalibration] trusts it); a center with no captured source (derived from the
     * other stickers, or none) is left unscaled.
     */
    internal fun normalized(
        sample: FaceSample,
        merged: List<FaceSample>,
        centers: Map<Color, Lab>,
    ): Map<Color, Lab> {
        val level = SideLight.level(sample.stickers.map { it.l }) ?: return centers
        return centers.mapValues { (color, center) ->
            val sourceLevel = sourceOf(color, merged)?.let { SideLight.level(it.stickers.map { sticker -> sticker.l }) }
            if (sourceLevel == null || sourceLevel <= 0.0) {
                center
            } else {
                center.copy(l = (center.l * level / sourceLevel).coerceIn(0.0, 100.0))
            }
        }
    }

    /** The captured face [color]'s center came from: the single sample with that center color, plain and usable; else null. */
    internal fun sourceOf(
        color: Color,
        merged: List<FaceSample>,
    ): FaceSample? =
        merged.singleOrNull { it.centerColor == color }?.takeIf { sample ->
            sample.centerPlain != false &&
                sample.quality == CaptureQuality.ACCEPTABLE &&
                sample.stickers.size == ScanAssembler.STICKERS &&
                sample.stickers.all { it.isValid() }
        }
}
