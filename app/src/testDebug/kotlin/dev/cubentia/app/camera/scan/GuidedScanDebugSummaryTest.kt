package dev.cubentia.app.camera.scan

import dev.cubentia.app.scan.GuidedScanCapture
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.GuidedScanPlan
import dev.cubentia.domain.recognition.Lab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GuidedScanDebugSummaryTest {
    private val labs =
        mapOf(
            Color.WHITE to Lab(90.0, 0.0, 0.0),
            Color.YELLOW to Lab(85.0, -5.0, 80.0),
            Color.GREEN to Lab(55.0, -60.0, 40.0),
            Color.BLUE to Lab(40.0, 10.0, -50.0),
            Color.RED to Lab(45.0, 65.0, 45.0),
            Color.ORANGE to Lab(65.0, 45.0, 65.0),
        )

    private val solved =
        GuidedScanPlan.steps.map { step ->
            FaceSample(
                face = step.face,
                orientationDegrees = 0,
                stickers = List(9) { labs.getValue(step.centerColor) },
                quality = CaptureQuality.ACCEPTABLE,
                centerColor = step.centerColor,
                upNeighborColor = step.upNeighborColor,
                centerPlain = true,
            )
        }

    @Test
    fun `summaries name the outcome`() {
        assertEquals("Cancelled.", summarize(GuidedScanCapture.Cancelled))
        assertEquals("Fell back to the editor.", summarize(GuidedScanCapture.FellBackToEditor))
        assertEquals("Full: 0 uncertain of 54.", summarize(GuidedScanCapture.Captured(solved)))
        assertEquals("5 samples; needs calibration for YELLOW.", summarize(solved.dropLast(1)))
        val noPose = listOf(solved.first().copy(upNeighborColor = Color.BLUE)) + solved.drop(1)
        assertEquals("Partial: 0 uncertain; gaps F=NO_POSE.", summarize(noPose))
    }
}
