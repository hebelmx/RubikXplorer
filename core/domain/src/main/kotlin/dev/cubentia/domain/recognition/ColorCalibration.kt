package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import java.util.Collections
import kotlin.math.atan2
import kotlin.math.hypot

object ColorPlausibility {
    fun plausible(
        color: Color,
        lab: Lab,
    ): Boolean {
        if (!lab.isValid()) return false
        val c = hypot(lab.a, lab.b)
        val h = (Math.toDegrees(atan2(lab.b, lab.a)) + 360) % 360
        return when (color) {
            Color.WHITE -> lab.l >= 60 && c <= 30
            Color.YELLOW -> lab.l >= 55 && c >= 45 && h in 80.0..125.0
            Color.ORANGE -> lab.l >= 40 && c >= 35 && h in 25.0..90.0
            Color.RED -> lab.l in 20.0..75.0 && c >= 20 && (h >= 340 || h <= 50)
            Color.GREEN -> lab.l >= 30 && c >= 20 && h in 115.0..185.0
            Color.BLUE -> lab.l >= 15 && c >= 20 && h in 215.0..315.0
        }
    }

    fun valid(calibration: CalibrationCenters): Boolean =
        StickerClassifier.isValid(calibration) &&
            calibration.centers.all { (color, lab) -> plausible(color, lab) } &&
            calibration.centers.values.toList().let { values ->
                values.indices.all { i ->
                    (i + 1 until values.size).all { j ->
                        StickerClassifier.deltaE(values[i], values[j]) >= StickerClassifier.CLOSE_CALL_DELTA_E
                    }
                }
            }
}

/** A swatch can advance only after an explicit Keep. No pixels or session state are held here. */
class CalibrationSession private constructor(
    kept: Map<Color, Lab>,
    val pending: Lab?,
    val plainSticker: Boolean,
) {
    constructor() : this(emptyMap(), null, false)

    val kept: Map<Color, Lab> = Collections.unmodifiableMap(kept.toMap())
    val current: Color? get() = GuidedScanPlan.steps.getOrNull(kept.size)?.centerColor
    val calibration: CalibrationCenters? get() =
        if (current == null) CalibrationCenters(kept) else null

    fun offer(reading: CellReading?): CalibrationSession {
        val color = current ?: return this
        val value = reading?.value
        return if (reading?.plain == true && value != null && ColorPlausibility.plausible(color, value) &&
            kept.values.all { StickerClassifier.deltaE(it, value) >= StickerClassifier.CLOSE_CALL_DELTA_E }
        ) {
            CalibrationSession(kept, value, plainSticker)
        } else {
            CalibrationSession(kept, null, true)
        }
    }

    fun keep(): CalibrationSession {
        val color = current ?: return this
        val value = pending ?: return this
        return CalibrationSession(kept + (color to value), null, false)
    }

    fun retake(): CalibrationSession = CalibrationSession(kept, null, plainSticker)
}

object CalibrationOffer {
    const val MANY_UNCERTAIN = 6

    fun offered(
        samples: List<FaceSample>,
        saved: CalibrationCenters?,
        review: ScanReview,
    ): Boolean =
        when (val result = SelfCalibration.from(samples, saved)) {
            is SelfCalibration.NeedsCalibration -> true
            is SelfCalibration.Calibrated -> (saved == null && result.guarded.isNotEmpty()) || review.blanks.size >= MANY_UNCERTAIN
        }
}
