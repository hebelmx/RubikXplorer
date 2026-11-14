package dev.cubentia.app.camera

import android.content.Context
import dev.cubentia.app.scan.ScanLauncher
import javax.inject.Inject

class CameraScanLauncher
    @Inject
    constructor() : ScanLauncher {
        override fun hasCamera(context: Context): Boolean = context.packageManager.hasSystemFeature("android.hardware.camera.any")
    }
