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

    /** Blocking denial-record read; call off the main thread. */
    fun cameraDenied(context: Context): Boolean = false

    /** The explicit intent of the Guided 6-face Scan; start it only through [ScanFlowContract]. */
    fun guidedScanIntent(context: Context): Intent

    /** The explicit intent of Color Calibration on its own (Settings); it finishes after *Save* or *Close*. */
    fun calibrationIntent(context: Context): Intent = guidedScanIntent(context)
}
