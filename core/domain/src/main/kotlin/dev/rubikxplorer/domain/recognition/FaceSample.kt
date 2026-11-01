package dev.rubikxplorer.domain.recognition

import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.Face
import kotlinx.serialization.Serializable

/** Neutral CIELAB measurement; no Android or image types cross into domain code. */
@Serializable
data class Lab(
    val l: Double,
    val a: Double,
    val b: Double,
) {
    fun isValid(): Boolean = l.isFinite() && a.isFinite() && b.isFinite() && l in 0.0..100.0
}

@Serializable
enum class CaptureQuality { ACCEPTABLE, BLURRED, GLARE, LOW_LIGHT, MISALIGNED, INCOMPLETE }

/** One oriented face capture. Sticker samples use row-major order, top-left through bottom-right. */
@Serializable
data class FaceSample(
    val schemaVersion: Int = 1,
    val face: Face,
    val orientationDegrees: Int,
    val stickers: List<Lab>,
    val quality: CaptureQuality,
    val qualityDetail: String? = null,
) {
    val center: Lab? get() = stickers.getOrNull(CENTER_INDEX)
    val upNeighbor: Lab? get() = stickers.getOrNull(UP_NEIGHBOR_INDEX)

    companion object {
        const val CENTER_INDEX = 4
        const val UP_NEIGHBOR_INDEX = 1
    }
}

@Serializable
data class LabeledFaceSample(
    val sample: FaceSample,
    val truth: List<Color>,
)

@Serializable
data class CalibrationCenters(
    val centers: Map<Color, Lab>,
)

@Serializable
data class ScanFixtureSet(
    val calibration: CalibrationCenters,
    val samples: List<LabeledFaceSample>,
)

@Serializable
data class FaceClassification(
    val colors: List<Color>,
    val correct: Int,
    val total: Int,
) {
    val accuracy: Double get() = if (total == 0) 0.0 else correct.toDouble() / total
}

/** Explicit failures prevent unavailable samples and missing truth from becoming guessed scores. */
sealed interface ClassificationResult {
    data class Classified(
        val value: FaceClassification,
    ) : ClassificationResult

    data class Unavailable(
        val reason: String,
    ) : ClassificationResult
}

object NearestCenterClassifier {
    fun classify(
        sample: FaceSample,
        calibration: CalibrationCenters,
        truth: List<Color>? = null,
    ): ClassificationResult {
        if (truth == null) return ClassificationResult.Unavailable("explicit nine-sticker ground truth is required for accuracy")
        if (truth.size != 9) return ClassificationResult.Unavailable("truth must contain nine explicit labels")
        return predict(sample, calibration).let { result ->
            when (result) {
                is ClassificationResult.Unavailable -> {
                    result
                }

                is ClassificationResult.Classified -> {
                    val correct =
                        result.value.colors
                            .zip(truth)
                            .count { (actual, expected) -> actual == expected }
                    ClassificationResult.Classified(FaceClassification(result.value.colors, correct, truth.size))
                }
            }
        }
    }

    /** Prediction is useful to the transient live view, but carries no accuracy without explicit truth. */
    fun predict(
        sample: FaceSample,
        calibration: CalibrationCenters,
    ): ClassificationResult {
        if (sample.schemaVersion != 1) return ClassificationResult.Unavailable("unsupported schema version")
        if (sample.orientationDegrees !in 0..359) return ClassificationResult.Unavailable("orientation must be between 0 and 359 degrees")
        if (sample.stickers.size != 9) return ClassificationResult.Unavailable("expected nine ordered sticker samples")
        if (sample.stickers.any { !it.isValid() }) return ClassificationResult.Unavailable("invalid CIELAB sample")
        if (sample.quality != CaptureQuality.ACCEPTABLE) {
            return ClassificationResult.Unavailable(sample.qualityDetail ?: "capture quality: ${sample.quality}")
        }
        if (calibration.centers.keys != Color.entries.toSet() || calibration.centers.values.any { !it.isValid() }) {
            return ClassificationResult.Unavailable("calibration must contain six valid color centers")
        }
        val colors =
            sample.stickers.map { sticker ->
                calibration.centers.minBy { (_, center) -> distanceSquared(sticker, center) }.key
            }
        return ClassificationResult.Classified(FaceClassification(colors, 0, 0))
    }

    private fun distanceSquared(
        left: Lab,
        right: Lab,
    ): Double =
        (left.l - right.l) * (left.l - right.l) +
            (left.a - right.a) * (left.a - right.a) +
            (left.b - right.b) * (left.b - right.b)
}
