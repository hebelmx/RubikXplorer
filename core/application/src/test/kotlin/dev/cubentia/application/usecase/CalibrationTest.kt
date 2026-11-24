package dev.cubentia.application.usecase

import dev.cubentia.application.port.CalibrationLoad
import dev.cubentia.application.port.CalibrationRepository
import dev.cubentia.application.port.CalibrationWrite
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.Lab
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CalibrationTest {
    private class Fake : CalibrationRepository {
        var value: CalibrationCenters? = null
        var writes = 0
        var failed = false

        override suspend fun load(): CalibrationLoad = if (failed) CalibrationLoad.Failed else CalibrationLoad.Loaded(value)

        override suspend fun save(calibration: CalibrationCenters): CalibrationWrite {
            writes++
            if (failed) return CalibrationWrite.Failed
            value = calibration
            return CalibrationWrite.Saved
        }

        override suspend fun reset(): CalibrationWrite {
            if (failed) return CalibrationWrite.Failed
            value = null
            return CalibrationWrite.Saved
        }
    }

    private val centers =
        CalibrationCenters(
            mapOf(
                Color.GREEN to Lab(55.0, -60.0, 40.0),
                Color.RED to Lab(45.0, 65.0, 45.0),
                Color.BLUE to Lab(40.0, 10.0, -50.0),
                Color.ORANGE to Lab(65.0, 45.0, 65.0),
                Color.WHITE to Lab(90.0, 0.0, 0.0),
                Color.YELLOW to Lab(85.0, -5.0, 80.0),
            ),
        )

    @Test fun `update refuses invalid implausible and overlapping centers without writing`() =
        runBlocking {
            val repo = Fake()
            val update = UpdateCalibration(repo)
            listOf(
                CalibrationCenters(emptyMap()),
                CalibrationCenters(centers.centers + (Color.WHITE to Lab(Double.NaN, 0.0, 0.0))),
                CalibrationCenters(centers.centers + (Color.WHITE to Lab(76.0, 20.0, 71.0))),
                CalibrationCenters(centers.centers + (Color.ORANGE to centers.centers.getValue(Color.RED))),
            ).forEach { assertEquals(CalibrationWrite.Rejected, update(it)) }
            assertEquals(0, repo.writes)
            assertEquals(CalibrationLoad.Loaded(null), repo.load())
            assertEquals(CalibrationWrite.Saved, update(centers))
            assertEquals(CalibrationLoad.Loaded(centers), repo.load())
            repo.failed = true
            assertEquals(CalibrationWrite.Failed, update(centers))
            assertEquals(CalibrationWrite.Failed, ResetCalibration(repo)())
            assertEquals(CalibrationLoad.Failed, repo.load())
            repo.failed = false
            assertEquals(CalibrationWrite.Saved, ResetCalibration(repo)())
            assertEquals(CalibrationLoad.Loaded(null), repo.load())
        }

    @Test fun `update keeps the torch tag the calibration was saved under`() =
        runBlocking {
            // Story 10.21: the tag travels through the use case and the port unchanged.
            val repo = Fake()
            val lit = centers.copy(torchOn = true)
            assertEquals(CalibrationWrite.Saved, UpdateCalibration(repo)(lit))
            assertEquals(CalibrationLoad.Loaded(lit), repo.load())
            assertEquals(CalibrationWrite.Saved, UpdateCalibration(repo)(centers))
            assertEquals(null, (repo.load() as CalibrationLoad.Loaded).calibration?.torchOn)
        }
}
