package dev.cubentia.app.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.PhysicalObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class QuickCheckContractTest {
    private val launcher =
        object : ScanLauncher {
            override fun hasCamera(context: Context) = true

            override fun guidedScanIntent(context: Context) = Intent("full")

            override fun quickCheckIntent(
                context: Context,
                faces: Set<Face>,
            ) = Intent("subset")
        }

    @Test fun subsetRequiresCalibrationAndFullRequestsAlwaysUseReview() {
        val contract = QuickCheckContract(launcher)
        val context = RuntimeEnvironment.getApplication()
        assertEquals("full", contract.createIntent(context, QuickCheckInput(setOf(Face.R))).action)
        assertEquals("subset", contract.createIntent(context, QuickCheckInput(setOf(Face.R), hasCalibration = true)).action)
        assertEquals("full", contract.createIntent(context, QuickCheckInput(setOf(Face.R), true, true)).action)
        assertEquals("full", contract.createIntent(context, QuickCheckInput(Face.entries.toSet(), hasCalibration = true)).action)
    }

    @Test fun partialRoundTripIncludesEmptyEvidenceAndFullUsesValidation() {
        listOf(PhysicalObservation.Partial(emptyMap()), PhysicalObservation.Partial(mapOf(Face.R to List(9) { Color.RED }))).forEach {
            val (code, data) = QuickCheckContract.encode(it)
            assertEquals(QuickCheckOutcome.Observed(it), QuickCheckContract.decode(code, data))
        }
        val (code, data) = ScanFlowContract.encode(ScanOutcome.Accepted(CubeState.SOLVED))
        assertEquals(QuickCheckOutcome.Observed(PhysicalObservation.Full(CubeState.SOLVED)), QuickCheckContract.decode(code, data))
        val (_, fallback) = ScanFlowContract.encode(ScanOutcome.FellBackToEditor())
        assertEquals(QuickCheckOutcome.Cancelled, QuickCheckContract.decode(ScanFlowContract.RESULT_FELL_BACK_TO_EDITOR, fallback))
        assertEquals(QuickCheckOutcome.Cancelled, QuickCheckContract.decode(Activity.RESULT_CANCELED, data))
    }

    @Test fun malformedNamesSizesAndDuplicateFacesCancel() {
        val valid = QuickCheckContract.encode(PhysicalObservation.Partial(mapOf(Face.R to List(9) { Color.RED }))).second
        listOf(
            Intent(valid).putExtra(QuickCheckContract.EXTRA_FACES, arrayOf("bad")),
            Intent(valid).putExtra(QuickCheckContract.EXTRA_FACES, arrayOf("R", "R")),
            Intent(valid).putExtra("dev.cubentia.app.scan.QUICK_CHECK_COLORS", arrayOf("RED")),
            Intent(valid).putExtra("dev.cubentia.app.scan.QUICK_CHECK_COLORS", Array(9) { "bad" }),
            Intent(),
        ).forEach { assertEquals(QuickCheckOutcome.Cancelled, QuickCheckContract.decode(Activity.RESULT_OK, it)) }
    }

    private val colors =
        CalibrationCenters(
            Color.entries.associateWith { color -> Lab(40.0 + color.ordinal, -20.0 + color.ordinal * 3, 10.5 * color.ordinal) },
        )

    @Test fun runScanColorsRouteASubsetOnlyWithoutCalibration() {
        val contract = QuickCheckContract(launcher)
        val context = RuntimeEnvironment.getApplication()
        val runScan = contract.createIntent(context, QuickCheckInput(setOf(Face.U, Face.R), runScanColors = colors))
        assertEquals("subset", runScan.action)
        assertEquals(colors, RunScanColorsExtra.get(runScan))
        // A saved calibration wins: today's subset intent, with no run-scan extra.
        val calibrated = contract.createIntent(context, QuickCheckInput(setOf(Face.R), hasCalibration = true, runScanColors = colors))
        assertEquals("subset", calibrated.action)
        assertFalse(calibrated.hasExtra(QuickCheckContract.EXTRA_RUN_SCAN_COLORS))
        // Full requests and 6 faces stay full, with no run-scan extra.
        listOf(
            QuickCheckInput(setOf(Face.R), fullScan = true, runScanColors = colors),
            QuickCheckInput(Face.entries.toSet(), runScanColors = colors),
            QuickCheckInput(setOf(Face.R)),
        ).forEach {
            val intent = contract.createIntent(context, it)
            assertEquals("full", intent.action)
            assertFalse(intent.hasExtra(QuickCheckContract.EXTRA_RUN_SCAN_COLORS))
        }
    }

    @Test fun acceptedCarriesRunScanColorsThroughBothContracts() {
        val (code, data) = ScanFlowContract.encode(ScanOutcome.Accepted(CubeState.SOLVED, colors))
        assertEquals(ScanOutcome.Accepted(CubeState.SOLVED, colors), ScanFlowContract.decode(code, data))
        assertEquals(
            QuickCheckOutcome.Observed(PhysicalObservation.Full(CubeState.SOLVED), colors),
            QuickCheckContract.decode(code, data),
        )
        val (plainCode, plain) = ScanFlowContract.encode(ScanOutcome.Accepted(CubeState.SOLVED))
        assertFalse(plain!!.hasExtra(QuickCheckContract.EXTRA_RUN_SCAN_COLORS))
        assertEquals(ScanOutcome.Accepted(CubeState.SOLVED), ScanFlowContract.decode(plainCode, plain))
    }

    @Test fun malformedRunScanColorsDecodeAsNull() {
        val (code, data) = ScanFlowContract.encode(ScanOutcome.Accepted(CubeState.SOLVED, colors))
        val invalidLab = RunScanColorsExtra.encode(colors).also { it[0] = 120.0 }
        val notFinite = RunScanColorsExtra.encode(colors).also { it[4] = Double.NaN }
        listOf(
            Intent(data).putExtra(QuickCheckContract.EXTRA_RUN_SCAN_COLORS, DoubleArray(17)),
            Intent(data).putExtra(QuickCheckContract.EXTRA_RUN_SCAN_COLORS, invalidLab),
            Intent(data).putExtra(QuickCheckContract.EXTRA_RUN_SCAN_COLORS, notFinite),
            Intent(data).putExtra(QuickCheckContract.EXTRA_RUN_SCAN_COLORS, "wrong type"),
            Intent(data).apply { removeExtra(QuickCheckContract.EXTRA_RUN_SCAN_COLORS) },
        ).forEach {
            assertNull(RunScanColorsExtra.get(it))
            assertEquals(ScanOutcome.Accepted(CubeState.SOLVED), ScanFlowContract.decode(code, it))
        }
        assertNull(RunScanColorsExtra.get(null))
    }
}
