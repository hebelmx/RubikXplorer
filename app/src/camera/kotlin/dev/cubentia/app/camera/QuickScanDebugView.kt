package dev.cubentia.app.camera

import dev.cubentia.app.ScanDiagnostics

/**
 * GH #409: the process-wide runtime toggle of the Quick Scan debug views (the peaks-and-lattice overlay; GH #414 can
 * reuse it), off by default. Only the debug adb receiver sets it; [enabled] also needs the compile-time
 * [ScanDiagnostics.QUICK_SCAN_DEBUG_VIEW] gate, false in release, so a release build never builds an overlay.
 */
internal object QuickScanDebugView {
    @Volatile private var on = false

    /** Whether the debug views are drawn: the build allows them and the toggle is on. */
    val enabled: Boolean get() = ScanDiagnostics.QUICK_SCAN_DEBUG_VIEW && on

    /** The toggle itself, whatever the build allows. */
    val toggled: Boolean get() = on

    fun set(value: Boolean) {
        on = value
    }

    /** Flips the toggle and returns its new value. */
    @Synchronized
    fun toggle(): Boolean {
        on = !on
        return on
    }
}
