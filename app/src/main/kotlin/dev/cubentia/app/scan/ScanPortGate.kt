package dev.cubentia.app.scan

import dev.cubentia.application.port.FeatureFlag
import dev.cubentia.application.port.FeatureFlags
import dev.cubentia.application.port.FlagLevel

/**
 * Story 10.9 scan-port gate (AD-9, AD-18): wraps the build's [FeatureFlags] so a camera flag reads
 * above OFF only when the camera source set bound a [ScanLauncher] and the device has a camera;
 * otherwise every camera flag reads OFF and every scan entry point stays hidden. A debuggable app
 * with a camera flag above OFF and no binding is a wiring bug and throws at construction.
 */
class ScanPortGate(
    private val delegate: FeatureFlags,
    bindingPresent: Boolean,
    debuggable: Boolean,
    cameraAvailable: Boolean = bindingPresent,
) : FeatureFlags {
    private val cameraAvailable = bindingPresent && cameraAvailable

    init {
        val enabled = cameraFlags.filter(delegate::isEnabled)
        check(!debuggable || bindingPresent || enabled.isEmpty()) {
            "Camera flags $enabled require a ScanLauncher binding"
        }
    }

    override fun level(flag: FeatureFlag): FlagLevel = if (flag in cameraFlags && !cameraAvailable) FlagLevel.OFF else delegate.level(flag)

    companion object {
        val cameraFlags = setOf(FeatureFlag.CAMERA_SCAN, FeatureFlag.QUICK_SCAN, FeatureFlag.PLACEMENT, FeatureFlag.CASE_RECOGNITION)
    }
}
