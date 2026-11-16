package dev.cubentia.app.camera

import android.app.Activity
import android.app.Application
import android.content.Intent
import dev.cubentia.app.scan.GuidedScanCapture
import dev.cubentia.app.scan.GuidedScanContract
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.GuidedScanPlan
import dev.cubentia.domain.recognition.Lab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Story 10.2: the typed scan result round-trips through an activity result. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GuidedScanContractTest {
    private val contract = GuidedScanContract(CameraScanLauncher())

    private fun roundTrip(capture: GuidedScanCapture): GuidedScanCapture {
        val (code, data) = GuidedScanContract.encode(capture)
        return contract.parseResult(code, data)
    }

    private val samples =
        GuidedScanPlan.steps.mapIndexed { index, step ->
            FaceSample(
                face = step.face,
                orientationDegrees = 90,
                stickers = List(9) { Lab(50.0 + index, it.toDouble(), -it.toDouble()) },
                quality = CaptureQuality.ACCEPTABLE,
                centerColor = step.centerColor,
                upNeighborColor = step.upNeighborColor,
                centerPlain = index != 2,
            )
        }

    @Test
    fun `six samples round-trip as Captured`() {
        assertEquals(GuidedScanCapture.Captured(samples), roundTrip(GuidedScanCapture.Captured(samples)))
    }

    @Test
    fun `not six samples, or centers out of plan order, read as Cancelled`() {
        assertEquals(GuidedScanCapture.Cancelled, roundTrip(GuidedScanCapture.Captured(samples.take(5))))
        assertEquals(GuidedScanCapture.Cancelled, roundTrip(GuidedScanCapture.Captured(samples + samples.last())))
        assertEquals(GuidedScanCapture.Cancelled, roundTrip(GuidedScanCapture.Captured(samples.reversed())))
        assertEquals(
            GuidedScanCapture.Cancelled,
            roundTrip(GuidedScanCapture.Captured(samples.mapIndexed { i, s -> if (i == 1) s.copy(centerColor = Color.BLUE) else s })),
        )
    }

    @Test
    fun `a sample without nine stickers reads as Cancelled`() {
        val eight = samples.mapIndexed { i, s -> if (i == 3) s.copy(stickers = s.stickers.take(8)) else s }
        assertEquals(GuidedScanCapture.Cancelled, roundTrip(GuidedScanCapture.Captured(eight)))
        val ten = samples.mapIndexed { i, s -> if (i == 0) s.copy(stickers = s.stickers + s.stickers.first()) else s }
        assertEquals(GuidedScanCapture.Cancelled, roundTrip(GuidedScanCapture.Captured(ten)))
    }

    @Test
    fun `Cancelled and FellBackToEditor round-trip`() {
        assertEquals(GuidedScanCapture.Cancelled, roundTrip(GuidedScanCapture.Cancelled))
        assertEquals(GuidedScanCapture.FellBackToEditor, roundTrip(GuidedScanCapture.FellBackToEditor))
    }

    @Test
    fun `a missing or malformed payload reads as Cancelled`() {
        assertEquals(GuidedScanCapture.Cancelled, contract.parseResult(Activity.RESULT_OK, null))
        assertEquals(
            GuidedScanCapture.Cancelled,
            contract.parseResult(Activity.RESULT_OK, Intent().putExtra(GuidedScanContract.EXTRA_SAMPLES, "{")),
        )
    }

    @Test
    fun `the contract starts the Guided Scan activity explicitly`() {
        val intent = contract.createIntent(RuntimeEnvironment.getApplication(), Unit)
        assertEquals(GuidedScanActivity::class.java.name, intent.component?.className)
        assertNotNull(
            "GuidedScanActivity is declared in the merged manifest",
            RuntimeEnvironment.getApplication().packageManager.resolveActivity(intent, 0),
        )
    }
}
