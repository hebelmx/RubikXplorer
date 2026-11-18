package dev.cubentia.app.scan

import android.app.Activity
import android.content.Intent
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.CornerView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaseCaptureContractTest {
    private val centerOnly = List(9) { if (it == 4) Color.YELLOW else null }
    private val corner = CornerView(centerOnly, List(9) { Color.GREEN }, List(9) { Color.ORANGE })

    @Test
    fun roundTripsBothCaptureSizesWithUnseenStickers() {
        for (size in 1..2) {
            val views = List(size) { corner }
            val (code, data) = CaseCaptureContract.encode(views)
            assertEquals(CaseCaptureOutcome.Captured(views), CaseCaptureContract.decode(code, data))
            assertEquals(CaseCaptureOutcome.Cancelled, CaseCaptureContract.decode(Activity.RESULT_CANCELED, data))
        }
        val (code, data) = CaseCaptureContract.editor()
        assertEquals(CaseCaptureOutcome.FellBackToEditor, CaseCaptureContract.decode(code, data))
    }

    @Test
    fun missingAndMalformedResultsCancel() {
        val colors = "dev.cubentia.app.scan.CASE_COLORS"
        val data =
            listOf(
                null,
                Intent(),
                Intent().putExtra(colors, arrayOf("WHITE")),
                Intent().putExtra(colors, Array(27) { "INVALID" }),
                Intent().putExtra(colors, Array(27) { "-" }),
            )
        data.forEach { assertEquals(CaseCaptureOutcome.Cancelled, CaseCaptureContract.decode(Activity.RESULT_OK, it)) }
    }
}
