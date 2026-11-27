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

    @Test
    fun `extended records retain the timing result`() {
        val record = "quickScan run elapsedMs=14999 fallbackFaces=0 repromptFaces=0 corrections=0 outcome=ACCEPTED"
        val state = "W".repeat(54)
        val report = QuickScanMeasurement.measure(sequenceOf("$record state=$state initial=$state device=SM-S721U1"))
        assertEquals(QuickScanMeasurement.Report(1, 1, 0, 0), report)
    }

    @Test
    fun `a run marked as ended from an early review is parsed like any other run`() {
        // The debug run line adds earlyReview=1 after the outcome (Story 10.32); the measurement does not read the mark.
        val record = "quickScan run elapsedMs=14999 fallbackFaces=0 repromptFaces=0 corrections=2 outcome=ACCEPTED earlyReview=1"
        val report = QuickScanMeasurement.measure(sequenceOf("D/CubentiaScanDiag: $record device=SM-S721U1"))
        assertEquals(QuickScanMeasurement.Report(1, 1, 0, 0), report)
    }

    @Test
    fun `untimed records count as unsuccessful runs`() {
        val report =
            QuickScanMeasurement.measure(
                sequenceOf("quickScan run elapsedMs=- fallbackFaces=0 repromptFaces=0 corrections=0 outcome=CANCELLED"),
            )
        assertEquals(QuickScanMeasurement.Report(1, 0, 0, 0), report)
    }

    private val timing =
        "D/CubentiaScanDiag: quickScan timing run frames=120 read=110 fps=29.8 detectorMs=21.0/30.5/41.2 " +
            "inferenceMs=18.0/26.1/37.9 fitMs=0.4/0.9/2.1 sampleMs=1.2/2.0/3.3 mapMs=0.3/0.6/1.0 analyzerMs=24.0/33.8/45.0 " +
            "fitted=140 observations=135 device=SM-S721U1"
    private val runLine =
        "D/CubentiaScanDiag: quickScan run elapsedMs=9000 fallbackFaces=0 repromptFaces=0 corrections=0 outcome=ACCEPTED device=SM-S721U1"

    @Test
    fun `a timing line attaches to the following run and a run without one counts as missing`() {
        val report = QuickScanMeasurement.measure(sequenceOf(runLine, timing, "quickScan timing window frames=1", runLine, runLine))
        assertEquals(3, report.runs)
        assertEquals(3, report.successes)
        assertEquals(2, report.runsMissingTimings)
        val t = report.runTimings.single()
        assertEquals(2, t.run)
        assertEquals(120, t.frames)
        assertEquals(110, t.read)
        assertEquals(29.8, t.fps)
        assertEquals(QuickScanMeasurement.Percentiles(21.0, 30.5, 41.2), t.detectorMs)
        assertEquals(QuickScanMeasurement.Percentiles(18.0, 26.1, 37.9), t.inferenceMs)
        assertEquals(QuickScanMeasurement.Percentiles(0.4, 0.9, 2.1), t.fitMs)
        assertEquals(QuickScanMeasurement.Percentiles(1.2, 2.0, 3.3), t.sampleMs)
        assertEquals(QuickScanMeasurement.Percentiles(0.3, 0.6, 1.0), t.mapMs)
        assertEquals(QuickScanMeasurement.Percentiles(24.0, 33.8, 45.0), t.analyzerMs)
    }

    @Test
    fun `a timing line without a following run is not attached`() {
        val report = QuickScanMeasurement.measure(sequenceOf(runLine, timing))
        assertEquals(1, report.runs)
        assertTrue(report.runTimings.isEmpty())
        assertEquals(1, report.runsMissingTimings)
    }

    @Test
    fun `dash fields parse as null`() {
        val skippedOnly =
            "quickScan timing run frames=1 read=0 fps=- detectorMs=- inferenceMs=- fitMs=- sampleMs=- mapMs=- " +
                "analyzerMs=3.0/3.0/3.0 fitted=0 observations=0 device=SM-S721U1"
        val t = QuickScanMeasurement.measure(sequenceOf(skippedOnly, runLine)).runTimings.single()
        assertEquals(null, t.fps)
        assertEquals(null, t.detectorMs)
        assertEquals(null, t.inferenceMs)
        assertEquals(null, t.fitMs)
        assertEquals(null, t.sampleMs)
        assertEquals(null, t.mapMs)
        assertEquals(QuickScanMeasurement.Percentiles(3.0, 3.0, 3.0), t.analyzerMs)
    }

    @Test
    fun `the markdown lists each run's timings and the runs missing them`() {
        val markdown = QuickScanMeasurement.measure(sequenceOf(timing, runLine, runLine)).markdown()
        assertTrue("- Runs missing timings: 1" in markdown, markdown)
        assertTrue(
            "| Run | Frames | Read | fps | detector ms | inference ms | fit ms | sample ms | map ms | analyzer ms |" in markdown,
            markdown,
        )
        assertTrue(
            "| 1 | 120 | 110 | 29.8 | 21.0/30.5/41.2 | 18.0/26.1/37.9 | 0.4/0.9/2.1 | 1.2/2.0/3.3 | 0.3/0.6/1.0 | 24.0/33.8/45.0 |" in
                markdown,
            markdown,
        )
        val untimed = QuickScanMeasurement.measure(sequenceOf(runLine)).markdown()
        assertTrue("- Runs missing timings: 1" in untimed, untimed)
        assertTrue("| Run |" !in untimed, untimed)
    }
}
