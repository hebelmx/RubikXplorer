package dev.cubentia.app.camera

import dev.cubentia.app.ScanDiagnostics
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** GH #409: the debug views' process toggle is off by default, and on only where the build's const gate allows it. */
class QuickScanDebugViewTest {
    @AfterEach fun off() = QuickScanDebugView.set(false)

    @Test
    fun `off by default, set and toggled at run time behind the debug gate`() {
        assertFalse(QuickScanDebugView.enabled)
        assertTrue(ScanDiagnostics.QUICK_SCAN_DEBUG_VIEW)
        QuickScanDebugView.set(true)
        assertTrue(QuickScanDebugView.enabled)
        assertEquals(false, QuickScanDebugView.toggle())
        assertFalse(QuickScanDebugView.enabled)
        assertEquals(true, QuickScanDebugView.toggle())
        assertTrue(QuickScanDebugView.enabled)
    }
}
