package dev.cubentia.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraPolicyTest {
    @Test
    fun `OFF rejects all camera ML and CV coordinate families`() {
        val coordinates =
            listOf(
                "androidx.camera:camera-core:1",
                "com.google.mlkit:vision:1",
                "com.google.ai.edge.litert:litert:1",
                "org.tensorflow.lite:runtime:1",
                "org.opencv:opencv:1",
            )
        assertEquals(coordinates.size, CameraClasspathPolicy.check(coordinates, false).size)
        assertTrue(CameraClasspathPolicy.check(coordinates, true).isEmpty())
        assertTrue(CameraClasspathPolicy.check(listOf("androidx.core:core:1"), false).isEmpty())
    }

    @Test
    fun `camera dependency rule names every offending flag`() {
        FeatureFlagLevels.cameraDependentFlags.forEach { flag ->
            val levels = FeatureFlagLevels.byBuildType.getValue("release") + (flag to "BETA")
            val violations = FeatureFlagLevels.cameraViolations("release", levels)
            assertEquals(1, violations.size)
            assertTrue(flag in violations.single() && "CAMERA_SCAN" in violations.single())
            assertTrue(FeatureFlagLevels.cameraViolations("release", levels + ("CAMERA_SCAN" to "BETA")).isEmpty())
        }
        assertTrue(!FeatureFlagLevels.isCameraEnabled(emptyMap()))
    }

    @Test
    fun `enabled nondebuggable allows camera alone and camera feature follows permission allowance`() {
        assertEquals(setOf(ManifestPolicy.CAMERA), ManifestPolicy.allowedRuntimePermissions(false, true))
        val manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<uses-feature android:name="android.hardware.camera.any"/>
<application android:allowBackup="false"/>
</manifest>"""
        assertTrue(ManifestPolicy.check(manifest, emptySet()).single().contains("android.hardware.camera.any"))
        assertTrue(ManifestPolicy.check(manifest, setOf(ManifestPolicy.CAMERA)).isEmpty())
    }

    @Test
    fun `ML Kit is always denylisted`() {
        val coordinates =
            listOf(
                "com.google.mlkit:vision:1",
                "com.google.android.gms:play-services-mlkit-face-detection:1",
            )
        assertEquals(2, SdkDenylistPolicy.check(coordinates).size)
    }
}
