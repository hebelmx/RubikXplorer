package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import kotlin.math.sqrt

/** Categorical Sticker Confidence; no numeric confidence or margin leaves the domain. */
enum class StickerConfidence { CONFIDENT, UNCERTAIN }

data class StickerReading(
    val color: Color,
    val confidence: StickerConfidence,
)

enum class StickerClassificationFailure { INVALID_SAMPLE, INVALID_CALIBRATION }

sealed interface StickerClassification {
    data class Classified(
        val reading: StickerReading,
    ) : StickerClassification

    data class Unavailable(
        val failure: StickerClassificationFailure,
    ) : StickerClassification
}

/**
 * OQ-7 decision K: nearest calibrated center (CIE76), ties to the first color in [Color.entries] order. A reading is
 * CONFIDENT only when the second-nearest center is at least [CLOSE_CALL_DELTA_E] farther than the nearest.
 */
object StickerClassifier {
    /** The close-call rule; Story 10.3's review prefill uses this same threshold. */
    const val CLOSE_CALL_DELTA_E = 10.0

    /** Six valid centers, one per color. */
    fun isValid(calibration: CalibrationCenters): Boolean =
        calibration.centers.keys == Color.entries.toSet() && calibration.centers.values.all { it.isValid() }

    fun classify(
        sticker: Lab,
        calibration: CalibrationCenters,
    ): StickerClassification =
        when {
            !isValid(calibration) -> {
                StickerClassification.Unavailable(StickerClassificationFailure.INVALID_CALIBRATION)
            }

            !sticker.isValid() -> {
                StickerClassification.Unavailable(StickerClassificationFailure.INVALID_SAMPLE)
            }

            else -> {
                StickerClassification.Classified(read(sticker, calibration.centers))
            }
        }

    /** Requires a valid sticker and six valid centers. */
    internal fun read(
        sticker: Lab,
        centers: Map<Color, Lab>,
    ): StickerReading {
        val (nearest, margin) = nearestWithMargin(sticker, centers)
        return StickerReading(nearest, if (margin >= CLOSE_CALL_DELTA_E) StickerConfidence.CONFIDENT else StickerConfidence.UNCERTAIN)
    }

    /** Nearest color and how much farther (ΔE) the second-nearest center is; internal by the categorical-only rule. */
    internal fun nearestWithMargin(
        sticker: Lab,
        centers: Map<Color, Lab>,
    ): Pair<Color, Double> {
        val distances = Color.entries.map { it to deltaE(sticker, centers.getValue(it)) }
        val nearest = distances.minBy { it.second }
        val second = distances.filter { it.first != nearest.first }.minOf { it.second }
        return nearest.first to second - nearest.second
    }

    internal fun deltaE(
        left: Lab,
        right: Lab,
    ): Double {
        val dl = left.l - right.l
        val da = left.a - right.a
        val db = left.b - right.b
        return sqrt(dl * dl + da * da + db * db)
    }
}
