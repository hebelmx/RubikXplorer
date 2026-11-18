package dev.cubentia.domain.recognition

import dev.cubentia.domain.recognition.evaluation.quickscan.QuickScanMeasurement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuickScanMeasurementTest {
    @Test
    fun `recommendation includes the 95 percent boundary and excludes slow edited and cancelled runs`() {
        val good = "D/CubentiaScanDiag: quickScan run elapsedMs=14999 fallbackFaces=1 repromptFaces=1 corrections=3 outcome=ACCEPTED"
        val slow = good.replace("14999", "15000")
        val edited = good.replace("corrections=3", "corrections=4")
        val cancelled = good.replace("ACCEPTED", "CANCELLED")
        val ga = QuickScanMeasurement.measure((List(19) { good } + slow + "quickScan frame detected=true").asSequence())
        assertEquals(QuickScanMeasurement.Report(20, 19, 20, 20), ga)
        assertTrue("Recommendation: GA" in ga.markdown())
        val beta = QuickScanMeasurement.measure(sequenceOf(good, slow, edited, cancelled))
        assertEquals(1, beta.successes)
        assertEquals("BETA", beta.recommendation)
        assertEquals("BETA", QuickScanMeasurement.measure(emptySequence()).recommendation)
    }
}
