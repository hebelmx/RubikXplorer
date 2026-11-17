package dev.cubentia.app.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.PhysicalObservation
import org.junit.Assert.assertEquals
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
        val (_, fallback) = ScanFlowContract.encode(ScanOutcome.FellBackToEditor)
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
}
