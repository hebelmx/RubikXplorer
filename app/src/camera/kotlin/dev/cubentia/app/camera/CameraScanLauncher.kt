package dev.cubentia.app.camera

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dev.cubentia.app.scan.QuickCheckContract
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.app.scan.ScanPortGate
import dev.cubentia.domain.cube.Face
import javax.inject.Inject

class CameraScanLauncher
    @Inject
    constructor() : ScanLauncher {
        /**
         * True only when the package reports a camera and `CameraManager` lists a back-facing one, so
         * [ScanPortGate] hides every scan entry point on a device without a usable camera.
         */
        override fun hasCamera(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
                runCatching {
                    val manager = context.getSystemService(CameraManager::class.java) ?: return false
                    manager.cameraIdList.any { id ->
                        manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_BACK
                    }
                }.getOrDefault(false)

        override fun cameraDenied(context: Context): Boolean =
            androidx.core.content.ContextCompat
                .checkSelfPermission(context, android.Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED &&
                CameraDenialRecord.denied(context)

        override fun caseCaptureIntent(
            context: Context,
            corners: Int,
        ): Intent = guidedScanIntent(context).putExtra(GuidedScanActivity.EXTRA_CASE_CAPTURE, corners)

        override fun quickScanIntent(context: Context): Intent =
            guidedScanIntent(context).putExtra(GuidedScanActivity.EXTRA_QUICK_SCAN, true)

        override fun quickCheckIntent(
            context: Context,
            faces: Set<Face>,
        ): Intent = guidedScanIntent(context).putExtra(QuickCheckContract.EXTRA_FACES, faces.map { it.name }.toTypedArray())

        override fun calibrationIntent(context: Context): Intent =
            guidedScanIntent(context).putExtra(GuidedScanActivity.CALIBRATION_MODE, true)

        override fun guidedScanIntent(context: Context): Intent = Intent(context, GuidedScanActivity::class.java)
    }
