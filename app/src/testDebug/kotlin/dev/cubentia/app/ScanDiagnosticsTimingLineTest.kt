package dev.cubentia.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/** GH #406: the debug `quickScan timing window` and `quickScan timing run` lines `evaluateQuickScan` parses. */
@RunWith(RobolectricTestRunner::class)
class ScanDiagnosticsTimingLineTest {
    private val body =
        "frames=120 read=110 fps=29.8 detectorMs=21.0/30.5/41.2 inferenceMs=18.0/26.1/37.9 fitMs=0.4/0.9/2.1 " +
            "sampleMs=1.2/2.0/3.3 mapMs=0.3/0.6/1.0 analyzerMs=24.0/33.8/45.0 fitted=140 observations=135"

    @Before fun clear() = ShadowLog.clear()

    private fun lines() = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }

    @Test fun `a window line carries the body after its prefix`() {
        ScanDiagnostics.onQuickScanTimingWindow(body)
        assertEquals(listOf("quickScan timing window $body"), lines())
    }

    @Test fun `a run line ends with the device and a null body logs nothing`() {
        ScanDiagnostics.onQuickScanTimingRun(null)
        assertTrue(lines().isEmpty())

        ScanDiagnostics.onQuickScanTimingRun(body)
        val line = lines().single()
        assertTrue(line, Regex("quickScan timing run ${Regex.escape(body)} device=[A-Za-z0-9-]+").matches(line))
        // The pattern of core/domain/src/evaluation/.../quickscan/QuickScanMeasurement.kt.
        val stage = "(\\d+\\.\\d/\\d+\\.\\d/\\d+\\.\\d|-)"
        val parsed =
            Regex(
                "quickScan timing run frames=(\\d+) read=(\\d+) fps=(\\d+\\.\\d|-) detectorMs=$stage inferenceMs=$stage " +
                    "fitMs=$stage sampleMs=$stage mapMs=$stage analyzerMs=$stage fitted=(\\d+) observations=(\\d+)" +
                    "(?: [A-Za-z][A-Za-z0-9]*=[^\\s=]+)*\\s*$",
            )
        val match = checkNotNull(parsed.find(line)) { line }
        assertEquals("120", match.groupValues[1])
        assertEquals("24.0/33.8/45.0", match.groupValues[9])
    }

    @Test fun `the timing run line is logged before the quick scan run line`() {
        ScanDiagnostics.onQuickScanTimingRun(body)
        ScanDiagnostics.onQuickScanRun(ScanDiagnostics.startRun(), 0, null, null, "ACCEPTED")
        val logged = lines()
        assertEquals(2, logged.size)
        assertTrue(logged[0], logged[0].startsWith("quickScan timing run frames="))
        assertTrue(logged[1], logged[1].startsWith("quickScan run elapsedMs="))
    }
}
