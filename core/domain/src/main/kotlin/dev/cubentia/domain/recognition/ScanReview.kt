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

/** An immutable review, with fixed home centers and no inferred colors. */
class ScanReview(
    stickers: List<Color?>,
    selected: Int?,
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
    val problems: List<ReviewProblem> get() =
        PieceCheck.problems(stickers) +
            facelets?.let(CubeValidator::problems).orEmpty().map { ReviewProblem(it.reason, it.positions) }
    val outlined: Set<Int> get() = problems.flatMap { it.positions }.toSet()
    val accepted: CubeState? get() = facelets?.let { (CubeValidator.validate(it) as? ValidationResult.Valid)?.state }

    override fun equals(other: Any?): Boolean = other is ScanReview && stickers == other.stickers && selected == other.selected

    override fun hashCode(): Int = 31 * stickers.hashCode() + (selected ?: -1)

    fun select(index: Int): ScanReview = if (editable(index)) ScanReview(stickers, index) else this

    fun paint(color: Color): ScanReview {
        val index = selected ?: return this
        val painted = stickers.toMutableList().also { it[index] = color }
        val next = (1..Facelets.SIZE).map { (index + it) % Facelets.SIZE }.firstOrNull { painted[it] == null } ?: index
        return ScanReview(painted, next)
    }

    companion object {
        private fun editable(index: Int): Boolean = index in 0 until Facelets.SIZE && index % 9 != 4

        fun from(result: ScanResult): ScanReview {
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
            val review = ScanReview(colors, null)
            return ScanReview(review.stickers, review.blanks.firstOrNull())
        }

        fun fromSamples(
            samples: List<FaceSample>,
            saved: CalibrationCenters? = null,
        ): ScanReview =
            when (val calibration = SelfCalibration.from(samples, saved)) {
                is SelfCalibration.Calibrated -> {
                    from(ScanAssembler.assemble(samples, calibration.calibration))
                }

                is SelfCalibration.NeedsCalibration -> {
                    from(ScanResult.Partial(PhysicalObservation.Partial(emptyMap()), emptyMap(), emptyMap()))
                }
            }
    }
}
