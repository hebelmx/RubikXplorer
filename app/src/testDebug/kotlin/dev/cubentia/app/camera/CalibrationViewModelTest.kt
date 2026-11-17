package dev.cubentia.app.camera

import dev.cubentia.application.port.CalibrationLoad
import dev.cubentia.application.port.CalibrationRepository
import dev.cubentia.application.port.CalibrationWrite
import dev.cubentia.application.usecase.UpdateCalibration
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CalibrationSession
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.ScanReview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationViewModelTest {
    @Before fun main() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun reset() {
        Dispatchers.resetMain()
    }

    private val labs =
        mapOf(
            Color.GREEN to Lab(55.0, -60.0, 40.0),
            Color.RED to Lab(45.0, 65.0, 45.0),
            Color.BLUE to Lab(40.0, 10.0, -50.0),
            Color.ORANGE to Lab(65.0, 45.0, 65.0),
            Color.WHITE to Lab(90.0, 0.0, 0.0),
            Color.YELLOW to Lab(85.0, -5.0, 80.0),
        )

    private fun ready(): CalibrationSession {
        var session = CalibrationSession()
        while (session.current != null) session = session.offer(CellReading(labs.getValue(session.current!!), true)).keep()
        return session
    }

    private class Repository : CalibrationRepository {
        var calls = 0
        var result = CompletableDeferred<CalibrationWrite>()

        override suspend fun load(): CalibrationLoad = CalibrationLoad.Loaded(null)

        override suspend fun reset(): CalibrationWrite = CalibrationWrite.Saved

        override suspend fun save(calibration: CalibrationCenters): CalibrationWrite {
            calls++
            return result.await()
        }
    }

    @Test fun saveFailureKeepsSwatchesAndRetryFromReviewRestartsWithFreshController() =
        runBlocking {
            val vm = GuidedScanViewModel()
            val repository = Repository()
            val update = UpdateCalibration(repository)
            val review = GuidedScanPhase.Review(ScanReview(List(54) { null }, 0), true)
            val calibration = GuidedScanPhase.Calibration(session = ready(), fromReview = review)
            vm.phase = calibration
            val previousController = vm.controller
            try {
                vm.saveCalibration(update)
                vm.saveCalibration(update)
                assertEquals(1, repository.calls)
                assertTrue(vm.saving)
                repository.result.complete(CalibrationWrite.Failed)
                withTimeout(5000) { while (vm.saving) delay(1) }
                val failed = vm.phase as GuidedScanPhase.Calibration
                assertTrue(failed.saveFailed)
                assertSame(calibration.session, failed.session)
                assertSame(review, failed.fromReview)
                repository.result = CompletableDeferred()
                vm.saveCalibration(update)
                repository.result.complete(CalibrationWrite.Saved)
                withTimeout(5000) { while (vm.saving) delay(1) }
                assertTrue(vm.phase is GuidedScanPhase.Viewfinder)
                assertNotSame(previousController, vm.controller)
                assertEquals(0, vm.controller.stepIndex)
                assertEquals(CalibrationCenters(labs), vm.saved)
                assertFalse(vm.calibrationFinished)
            } finally {
                vm.analyzerExecutor.shutdownNow()
            }
        }

    @Test fun savingFromSettingsFinishesOnlyAfterSuccessfulWrite() =
        runBlocking {
            val vm = GuidedScanViewModel()
            val repository = Repository()
            try {
                vm.phase = GuidedScanPhase.Calibration(session = ready())
                vm.saveCalibration(UpdateCalibration(repository))
                assertFalse(vm.calibrationFinished)
                repository.result.complete(CalibrationWrite.Saved)
                withTimeout(5000) { while (vm.saving) delay(1) }
                assertTrue(vm.calibrationFinished)
            } finally {
                vm.analyzerExecutor.shutdownNow()
            }
        }
}
