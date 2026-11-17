package dev.cubentia.app.data

import dev.cubentia.application.port.CalibrationLoad
import dev.cubentia.application.port.CalibrationRepository
import dev.cubentia.application.port.CalibrationWrite
import dev.cubentia.domain.recognition.CalibrationCenters
import kotlinx.coroutines.CancellationException

class RoomCalibrationRepository(
    private val database: RubikDatabase,
) : CalibrationRepository {
    override suspend fun load(): CalibrationLoad =
        try {
            CalibrationLoad.Loaded(database.calibration().load()?.calibration())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CalibrationLoad.Failed
        }

    override suspend fun save(calibration: CalibrationCenters): CalibrationWrite =
        attempt {
            database.calibration().save(ColorCalibrationEntity.of(calibration))
        }

    override suspend fun reset(): CalibrationWrite = attempt { database.calibration().reset() }

    private suspend fun attempt(block: suspend () -> Unit): CalibrationWrite =
        try {
            block()
            CalibrationWrite.Saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CalibrationWrite.Failed
        }
}
