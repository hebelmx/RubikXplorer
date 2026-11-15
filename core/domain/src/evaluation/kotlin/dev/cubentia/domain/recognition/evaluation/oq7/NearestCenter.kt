package dev.cubentia.domain.recognition.evaluation.oq7

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.Lab
import kotlin.math.sqrt

/** Categorical confidence, as it would cross a module boundary. */
enum class Confidence { CONFIDENT, UNCERTAIN }

data class Prediction(
    val color: Color,
    val confidence: Confidence,
)

/** The `NearestCenterClassifier` rule plus the recorder's close-call confidence. */
object NearestCenter {
    /** Same close-call rule as the recorder prefill: the second-nearest center must be this much farther (CIE76). */
    const val CLOSE_CALL_DELTA_E = 10.0

    fun deltaE(
        left: Lab,
        right: Lab,
    ): Double = sqrt(distanceSquared(left, right))

    /** Nearest center wins (ties resolve in [centers] iteration order, as in `NearestCenterClassifier`). */
    fun predict(
        sample: Lab,
        centers: Map<Color, Lab>,
    ): Prediction {
        require(centers.keys == Color.entries.toSet()) { "Calibration must contain six color centers" }
        val nearest = centers.minBy { (_, center) -> distanceSquared(sample, center) }
        val second = centers.filterKeys { it != nearest.key }.values.minOf { deltaE(sample, it) }
        val margin = second - deltaE(sample, nearest.value)
        return Prediction(nearest.key, if (margin >= CLOSE_CALL_DELTA_E) Confidence.CONFIDENT else Confidence.UNCERTAIN)
    }

    private fun distanceSquared(
        left: Lab,
        right: Lab,
    ): Double =
        (left.l - right.l) * (left.l - right.l) +
            (left.a - right.a) * (left.a - right.a) +
            (left.b - right.b) * (left.b - right.b)
}

/** One face's center reading and the color its truth gives it (the face prompt fixes it in a product scan). */
data class CenterReading(
    val face: Face,
    val color: Color,
    val value: Lab,
)

sealed interface SelfCalibration {
    data class Centers(
        val centers: Map<Color, Lab>,
    ) : SelfCalibration

    data class Excluded(
        val reason: String,
    ) : SelfCalibration
}

object Calibrations {
    /** The non-white patch session A's first Lenovo `stka` run calibrated as WHITE. */
    val BAD_WHITE = Lab(76.0, 20.0, 71.0)
    val SWEEP = listOf(0.0, 0.25, 0.5, 0.75, 1.0)

    /** Calibration from the six face centers; no calibration step. A missing face or a repeated center color excludes the group. */
    fun self(readings: List<CenterReading>): SelfCalibration {
        val missing = Face.entries.filter { face -> readings.none { it.face == face } }
        if (missing.isNotEmpty()) return SelfCalibration.Excluded("missing face ${missing.joinToString("/") { it.name }}")
        val repeated =
            readings
                .groupBy { it.color }
                .filterValues { it.size > 1 }
                .entries
                .sortedBy { it.key }
        if (repeated.isNotEmpty()) {
            return SelfCalibration.Excluded(
                repeated.joinToString("; ") { (color, faces) -> "faces ${faces.joinToString("/") { it.face.name }} share a $color center" },
            )
        }
        val byColor = readings.associateBy { it.color }
        return SelfCalibration.Centers(Color.entries.associateWith { byColor.getValue(it).value })
    }

    /** [calibration] with WHITE replaced by [white]; the other five centers and the iteration order are unchanged. */
    fun withWhite(
        calibration: Map<Color, Lab>,
        white: Lab = BAD_WHITE,
    ): Map<Color, Lab> = LinkedHashMap(calibration).apply { put(Color.WHITE, white) }

    /** WHITE(t) = (1 − t)·recorded + t·bad. */
    fun blend(
        recorded: Lab,
        t: Double,
        bad: Lab = BAD_WHITE,
    ): Lab = Lab((1 - t) * recorded.l + t * bad.l, (1 - t) * recorded.a + t * bad.a, (1 - t) * recorded.b + t * bad.b)
}
