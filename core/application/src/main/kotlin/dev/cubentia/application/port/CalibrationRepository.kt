package dev.cubentia.application.port

import dev.cubentia.domain.recognition.CalibrationCenters

interface CalibrationRepository {
    suspend fun load(): CalibrationLoad

    suspend fun save(calibration: CalibrationCenters): CalibrationWrite

    suspend fun reset(): CalibrationWrite
}

sealed interface CalibrationLoad {
    data class Loaded(
        val calibration: CalibrationCenters?,
    ) : CalibrationLoad

    data object Failed : CalibrationLoad
}

sealed interface CalibrationWrite {
    data object Saved : CalibrationWrite

    data object Rejected : CalibrationWrite

    data object Failed : CalibrationWrite
}
