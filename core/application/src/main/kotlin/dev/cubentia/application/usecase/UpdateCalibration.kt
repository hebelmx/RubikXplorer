package dev.cubentia.application.usecase

import dev.cubentia.application.port.CalibrationRepository
import dev.cubentia.application.port.CalibrationWrite
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.ColorPlausibility

class UpdateCalibration(
    private val repository: CalibrationRepository,
) {
    suspend operator fun invoke(calibration: CalibrationCenters): CalibrationWrite =
        if (ColorPlausibility.valid(calibration)) repository.save(calibration) else CalibrationWrite.Rejected
}
