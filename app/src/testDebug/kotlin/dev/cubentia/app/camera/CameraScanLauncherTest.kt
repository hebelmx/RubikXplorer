package dev.cubentia.app.camera

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics

/** Story 10.2: scan entry points need a usable back camera. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CameraScanLauncherTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val launcher = CameraScanLauncher()

    private fun addCamera(
        id: String,
        facing: Int,
    ) {
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        shadowOf(characteristics).set(CameraCharacteristics.LENS_FACING, facing)
        shadowOf(context.getSystemService(CameraManager::class.java)).addCamera(id, characteristics)
    }

    private fun cameraFeature(present: Boolean) =
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, present)

    @Test
    fun `no camera feature means no camera`() {
        cameraFeature(false)
        addCamera("0", CameraCharacteristics.LENS_FACING_BACK)
        assertFalse(launcher.hasCamera(context))
    }

    @Test
    fun `a front camera alone is not a usable camera`() {
        cameraFeature(true)
        addCamera("1", CameraCharacteristics.LENS_FACING_FRONT)
        assertFalse(launcher.hasCamera(context))
    }

    @Test
    fun `no listed camera is not a usable camera`() {
        cameraFeature(true)
        assertFalse(launcher.hasCamera(context))
    }

    @Test
    fun `a back camera is a usable camera`() {
        cameraFeature(true)
        addCamera("1", CameraCharacteristics.LENS_FACING_FRONT)
        addCamera("0", CameraCharacteristics.LENS_FACING_BACK)
        assertTrue(launcher.hasCamera(context))
    }
}
