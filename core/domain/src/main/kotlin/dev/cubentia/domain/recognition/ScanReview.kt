package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.PieceCheck
import dev.cubentia.domain.cube.ReviewProblem
import dev.cubentia.domain.cube.ValidationResult
import java.util.Collections

/**
 * An immutable review, with fixed home centers and no inferred colors. [evidence] (#174) is what the scan measured; it
 * only ranks [suspects] when the color counts are off and is never shown as a number. A sticker keeps its Lab only
 * while it holds the scanned color: [paint] drops that index's Lab.
 */
class ScanReview(
    stickers: List<Color?>,
    selected: Int?,
    val evidence: ScanEvidence? = null,
) {
    val stickers: List<Color?> =
        Collections.unmodifiableList(
            List(Facelets.SIZE) { i ->
                if (i % 9 == 4) Color.home(Face.entries[i / 9]) else stickers[i]
            },
        )
    val selected: Int? = selected?.takeIf(::editable)
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
        other is ScanReview && stickers == other.stickers && selected == other.selected && evidence == other.evidence

    override fun hashCode(): Int = 31 * (31 * stickers.hashCode() + (selected ?: -1)) + evidence.hashCode()

    fun select(index: Int): ScanReview = if (editable(index)) ScanReview(stickers, index, evidence) else this

    fun paint(color: Color): ScanReview {
        val index = selected ?: return this
        val painted = stickers.toMutableList().also { it[index] = color }
        val next = (1..Facelets.SIZE).map { (index + it) % Facelets.SIZE }.firstOrNull { painted[it] == null }
        val kept = evidence?.without(setOf(index))
        if (next != null) return ScanReview(painted, next, kept)
        // The last blank is set: if the counts are now off, move to the top suspect (#174).
        val review = ScanReview(painted, index, kept)
        return review.suspects.firstOrNull()?.let { review.select(it.index) } ?: review
    }

    /**
     * This review with each [corrections] sticker set to its color (the Learner's edits, e.g. from the one-face grid):
     * those stickers lose their Lab, the rest of the [evidence] is kept, and the selection follows the same rule as
     * [from].
     */
    fun corrected(corrections: Map<Int, Color>): ScanReview {
        val colors = stickers.mapIndexed { i, color -> corrections[i] ?: color }
        return preselected(colors, evidence?.without(corrections.keys))
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

        /** Selects the first blank, else the top suspect, else the first sticker of the first problem naming stickers. */
        private fun preselected(
            colors: List<Color?>,
            evidence: ScanEvidence?,
        ): ScanReview {
            val review = ScanReview(colors, null, evidence)
            val selected =
                review.blanks.firstOrNull()
                    ?: review.suspects.firstOrNull()?.index
                    ?: review.problems
                        .firstOrNull { it.positions.isNotEmpty() }
                        ?.positions
                        ?.first()
            return ScanReview(review.stickers, selected, evidence)
        }

        private fun ScanEvidence.without(indices: Set<Int>): ScanEvidence =
            copy(labs = labs.mapIndexed { i, lab -> lab.takeUnless { i in indices } })

        fun fromSamples(
            samples: List<FaceSample>,
            saved: CalibrationCenters? = null,
        ): ScanReview =
            when (val calibration = SelfCalibration.from(samples, saved)) {
                is SelfCalibration.Calibrated -> {
                    from(ScanAssembler.assemble(samples, calibration.calibration), calibration.calibration)
                }

                is SelfCalibration.NeedsCalibration -> {
                    from(ScanResult.Partial(PhysicalObservation.Partial(emptyMap()), emptyMap(), emptyMap()))
                }
            }
    }
}
