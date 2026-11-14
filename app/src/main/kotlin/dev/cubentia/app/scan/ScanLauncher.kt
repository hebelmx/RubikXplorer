package dev.cubentia.app.scan

import android.content.Context

/**
 * Scan port (AD-9): the only way `:app` main reaches the camera. Bound optionally by the
 * `app/src/camera/` source set, which build-logic composes only into build types with a camera
 * flag above OFF; with no binding, [ScanPortGate] hides every scan entry point.
 */
interface ScanLauncher {
    fun hasCamera(context: Context): Boolean
}
