package dev.cubentia.app.scan

import android.content.Context
import android.content.Intent

/**
 * Scan port (AD-9): the only way `:app` main reaches the camera. Bound optionally by the
 * `app/src/camera/` source set, which build-logic composes only into build types with a camera
 * flag above OFF; with no binding, [ScanPortGate] hides every scan entry point.
 */
interface ScanLauncher {
    /** True only when the device has a usable back camera. */
    fun hasCamera(context: Context): Boolean

    /** The explicit intent of the Guided 6-face Scan; start it only through [GuidedScanContract]. */
    fun guidedScanIntent(context: Context): Intent
}
