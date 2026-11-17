package dev.cubentia.application.usecase

import dev.cubentia.application.port.CalibrationRepository
import dev.cubentia.application.port.CalibrationWrite

class ResetCalibration(
    private val repository: CalibrationRepository,
) {
    suspend operator fun invoke(): CalibrationWrite = repository.reset()
}
