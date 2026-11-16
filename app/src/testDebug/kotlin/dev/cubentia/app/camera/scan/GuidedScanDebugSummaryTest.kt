package dev.cubentia.app.camera.scan

import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.domain.cube.CubeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GuidedScanDebugSummaryTest {
    @Test fun `summaries name every typed outcome`() {
        assertEquals("Cancelled.", summarize(ScanOutcome.Cancelled))
        assertEquals("Fell back to the editor.", summarize(ScanOutcome.FellBackToEditor))
        assertEquals("Accepted.", summarize(ScanOutcome.Accepted(CubeState.SOLVED)))
    }
}
