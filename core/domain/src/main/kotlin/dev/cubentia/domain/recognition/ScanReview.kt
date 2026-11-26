package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.PieceCheck
import dev.cubentia.domain.cube.ReviewProblem
import dev.cubentia.domain.cube.StickerOrder
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff
import java.util.Collections

/**
 * An immutable review, with fixed home centers and no inferred colors. [evidence] (#174) is what the scan measured; it
 * only ranks [suspects] when the color counts are off and is never shown as a number. A sticker keeps its Lab only
 * while it holds the scanned color: [paint] drops that index's Lab.
 *
 * [checkFirst] (Story 10.32, *Review and Correct*) are the stickers to check before any other: the conflicted stickers
 * of an unfinished continuous scan. While one of them is still blank it is the sticker pre-selected and the one the
 * selection moves to after a color is set, ahead of every other blank; once none is blank the usual order applies.
 * Nothing marks them on screen, and with none every review behaves as it did before.
 */
class ScanReview(
    stickers: List<Color?>,
    selected: Int?,
    val evidence: ScanEvidence? = null,
    checkFirst: Set<Int> = emptySet(),
) {
    val stickers: List<Color?> =
        Collections.unmodifiableList(
            List(Facelets.SIZE) { i ->
                if (i % 9 == 4) Color.home(Face.entries[i / 9]) else stickers[i]
            },
        )
    val selected: Int? = selected?.takeIf(::editable)
    val checkFirst: Set<Int> =
        if (checkFirst.isEmpty()) emptySet() else Collections.unmodifiableSet(checkFirst.filterTo(sortedSetOf(), ::editable))
    val blanks: List<Int> get() = stickers.indices.filter { stickers[it] == null }
    private val facelets: Facelets? get() = if (blanks.isEmpty()) Facelets(stickers.filterNotNull()) else null

    /** Stickers that likely explain wrong color counts, best first ([SuspectRanking]); empty without [evidence]. */
    val suspects: List<Suspect> get() = evidence?.let { SuspectRanking.rank(stickers, it) }.orEmpty()

    /** The wrong-counts problem lists the [suspects] as its stickers, in rank order (not sorted). */
    val problems: List<ReviewProblem> get() =
        PieceCheck.problems(stickers) +
            facelets?.let(CubeValidator::problems).orEmpty().map { invalid ->
                val positions =
                    if (invalid is ValidationResult.WrongColorCounts) suspects.map { it.index } else invalid.positions
                ReviewProblem(invalid.reason, positions, piece = positions)
            }
    val outlined: Set<Int> get() = problems.flatMap { it.piece }.toSet()
    val accepted: CubeState? get() = facelets?.let { (CubeValidator.validate(it) as? ValidationResult.Valid)?.state }

    override fun equals(other: Any?): Boolean =
        other is ScanReview &&
            stickers == other.stickers &&
            selected == other.selected &&
            evidence == other.evidence &&
            checkFirst == other.checkFirst

    override fun hashCode(): Int = 31 * (31 * (31 * stickers.hashCode() + (selected ?: -1)) + evidence.hashCode()) + checkFirst.hashCode()

    fun select(index: Int): ScanReview = if (editable(index)) ScanReview(stickers, index, evidence, checkFirst) else this

    fun paint(color: Color): ScanReview {
        val index = selected ?: return this
        val painted = stickers.toMutableList().also { it[index] = color }
        // A sticker to check first that is still blank comes before any other blank (Story 10.32).
        val next =
            StickerOrder.next(index, wrap = true) { painted[it] == null && it in checkFirst }
                ?: StickerOrder.next(index, wrap = true) { painted[it] == null }
        val kept = evidence?.without(setOf(index))
        if (next != null) return ScanReview(painted, next, kept, checkFirst)
        // The last blank is set: if the counts are now off, move to the top suspect (#174).
        val review = ScanReview(painted, index, kept, checkFirst)
        return review.suspects.firstOrNull()?.let { review.select(it.index) } ?: review
    }

    /**
     * This review with each [corrections] sticker set to its color (the Learner's edits, e.g. from the one-face grid):
     * those stickers lose their Lab, the rest of the [evidence] is kept, and the selection follows the same rule as
     * [from].
     */
    fun corrected(corrections: Map<Int, Color>): ScanReview {
        val colors = stickers.mapIndexed { i, color -> corrections[i] ?: color }
        return preselected(colors, evidence?.without(corrections.keys), checkFirst)
    }

    companion object {
        private fun editable(index: Int): Boolean = index in 0 until Facelets.SIZE && index % 9 != 4

        /**
         * The review of [result]. With [centers] and a [ScanResult.Full] carrying Labs, the review keeps them as
         * [evidence]. Pre-selects the first blank, else the top suspect, else the first sticker of the first problem
         * that names stickers.
         */
        fun from(
            result: ScanResult,
            centers: CalibrationCenters? = null,
        ): ScanReview {
            val colors =
                List(Facelets.SIZE) { i ->
                    when (result) {
                        is ScanResult.Full -> {
                            result.facelets.colors[i].takeIf {
                                result.confidence.getOrNull(i) == StickerConfidence.CONFIDENT
                            }
                        }

                        is ScanResult.Partial -> {
                            val face = Face.entries[i / 9]
                            result.observation.faces[face]?.getOrNull(i % 9)?.takeIf {
                                face !in result.gaps &&
                                    result.confidence[face]?.getOrNull(i % 9) == StickerConfidence.CONFIDENT
                            }
                        }
                    }
                }
            val labs = (result as? ScanResult.Full)?.labs?.takeIf { it.size == Facelets.SIZE }
            val evidence = if (labs != null && centers != null) ScanEvidence(labs, centers.centers) else null
            return preselected(colors, evidence)
        }

        /**
         * The review of a continuous scan [handoff]. Preserves all canonical `Orientation.HOME`
         * confident stickers and carries the six self-calibrated center Labs plus 54 cell Labs as
         * [evidence] for `#323` suspect ranking and Story 10.16 face retakes without requiring a
         * saved FR-24 calibration. [checkFirst] (Story 10.32, *Review and Correct*) are the sticker indices to check
         * before any other, e.g. the conflicted stickers of an unfinished scan: see [ScanReview.checkFirst].
         */
        fun from(
            handoff: ContinuousScanHandoff,
            checkFirst: Set<Int> = emptySet(),
        ): ScanReview {
            val colors =
                List(Facelets.SIZE) { i ->
                    if (!handoff.globalAnchored) {
                        null
                    } else {
                        val face = Face.entries[i / 9]
                        val cell = i % 9
                        val fh = handoff.faces[face]
                        if (fh == null || (cell != 4 && !fh.orientationLocked)) {
                            null
                        } else {
                            fh.colors.getOrNull(cell)?.takeIf {
                                fh.confidence.getOrNull(cell) == StickerConfidence.CONFIDENT
                            }
                        }
                    }
                }
            val calibration = handoff.calibration?.takeIf(StickerClassifier::isValid)
            val evidence =
                calibration?.let { cal ->
                    val labs =
                        List(Facelets.SIZE) { i ->
                            if (!handoff.globalAnchored) {
                                null
                            } else {
                                val face = Face.entries[i / 9]
                                val cell = i % 9
                                val fh = handoff.faces[face]
                                if (fh == null || (cell != 4 && !fh.orientationLocked)) {
                                    null
                                } else {
                                    fh.labs
                                        .getOrNull(cell)
                                        ?.takeIf { it.isValid() }
                                }
                            }
                        }
                    ScanEvidence(labs, cal.centers)
                }
            return preselected(colors, evidence, checkFirst)
        }

        /**
         * Selects the first blank among [checkFirst], else the first blank, else the top suspect, else the first sticker
         * of the first problem naming stickers.
         */
        private fun preselected(
            colors: List<Color?>,
            evidence: ScanEvidence?,
            checkFirst: Set<Int> = emptySet(),
        ): ScanReview {
            val review = ScanReview(colors, null, evidence, checkFirst)
            val blanks = review.blanks
            val selected =
                blanks.firstOrNull { it in review.checkFirst }
                    ?: blanks.firstOrNull()
                    ?: review.suspects.firstOrNull()?.index
                    ?: review.problems
                        .firstOrNull { it.positions.isNotEmpty() }
                        ?.positions
                        ?.first()
            return ScanReview(review.stickers, selected, evidence, review.checkFirst)
        }

        private fun ScanEvidence.without(indices: Set<Int>): ScanEvidence =
            copy(labs = labs.mapIndexed { i, lab -> lab.takeUnless { i in indices } })

        /**
         * The review after a retake from [before] (Story 10.16): [retaken] replaces the samples of its faces in the
         * scan's [samples], and self-calibration runs over that merged set (with [saved]). Kept faces (those not
         * retaken) keep [before]'s stickers exactly, the Learner's edits included, and are never reclassified; retaken
         * faces come from [ScanAssembler.assembleRetake] with kept faces fixed, prefilled by the close-call rule, and
         * keep no earlier edit. Evidence is [before]'s Labs on kept faces and [ScanAssembler.assembleRetake]'s Labs on retaken
         * faces: measured for a placed retaken face, calibration-center placeholders for one that could not be placed. When the
         * merged samples cannot self-calibrate, retaken faces are blank. Selection follows the [from] rule; the
         * stickers of retaken faces leave [checkFirst] (a retaken face is a new read, with nothing left to check first).
         */
        fun retaken(
            before: ScanReview,
            retaken: List<FaceSample>,
            samples: List<FaceSample>,
            saved: CalibrationCenters? = null,
        ): ScanReview {
            val faces = retaken.map { it.face }.toSet()
            val kept = Face.entries.filter { it !in faces }
            val keptColors = kept.associateWith { face -> before.stickers.subList(face.ordinal * 9, face.ordinal * 9 + 9) }
            val retakenIndices = faces.flatMap { face -> face.ordinal * 9 until face.ordinal * 9 + 9 }.toSet()
            val checkFirst = before.checkFirst - retakenIndices
            val fallbackSaved =
                saved ?: before.evidence
                    ?.takeIf { samples.size < Face.entries.size }
                    ?.centers
                    ?.let(::CalibrationCenters)
                    ?.takeIf(StickerClassifier::isValid)
            val calibration =
                (SelfCalibration.from(mergedSamples(samples, retaken), fallbackSaved) as? SelfCalibration.Calibrated)?.calibration
                    ?: return preselected(
                        before.stickers.mapIndexed { i, color -> color.takeUnless { i in retakenIndices } },
                        before.evidence?.without(retakenIndices),
                        checkFirst,
                    )
            val result = ScanAssembler.assembleRetake(keptColors, retaken, calibration)
            val colors =
                List(Facelets.SIZE) { i ->
                    if (i in retakenIndices) {
                        result.facelets.colors[i].takeIf { result.confidence[i] == StickerConfidence.CONFIDENT }
                    } else {
                        before.stickers[i]
                    }
                }
            val labs = List(Facelets.SIZE) { i -> if (i in retakenIndices) result.labs?.get(i) else before.evidence?.labs?.get(i) }
            return preselected(colors, ScanEvidence(labs, calibration.centers), checkFirst)
        }

        /** [samples] with each face in [retaken] replaced by its retaken sample, in [samples] order (new faces last). */
        fun mergedSamples(
            samples: List<FaceSample>,
            retaken: List<FaceSample>,
        ): List<FaceSample> {
            val byFace = retaken.associateBy { it.face }
            return samples.map { byFace[it.face] ?: it } + retaken.filter { sample -> samples.none { it.face == sample.face } }
        }

        /**
         * The review of a finished scan's [samples], self-calibrated over [saved]. [blanks] (Story 10.21, Quick Scan) are
         * the kept corner faces' gated-uncertain stickers (viewfinder order): they stay blank, so the first is pre-selected.
         */
        fun fromSamples(
            samples: List<FaceSample>,
            saved: CalibrationCenters? = null,
            blanks: Map<Face, Set<Int>> = emptyMap(),
        ): ScanReview =
            when (val calibration = SelfCalibration.from(samples, saved)) {
                is SelfCalibration.Calibrated -> {
                    from(ScanAssembler.assemble(samples, calibration.calibration, blanks), calibration.calibration)
                }

                is SelfCalibration.NeedsCalibration -> {
                    from(ScanResult.Partial(PhysicalObservation.Partial(emptyMap()), emptyMap(), emptyMap()))
                }
            }
    }
}
