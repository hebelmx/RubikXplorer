package dev.cubentia.buildlogic.functional

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraCompositionFunctionalTest {
    /** [AndroidFixture] writes `src/camera/AndroidManifest.xml` and the fake CameraX catalog modules. */
    private fun fixture(name: String) = AndroidFixture("camera-$name", plugins = listOf("cubentia.release", "cubentia.quality"))

    private fun append(
        fixture: AndroidFixture,
        script: String,
    ) = fixture.write(
        "build.gradle.kts",
        fixture.projectDir.resolve("build.gradle.kts").readText() + "\n" + script,
    )

    @Test
    fun `today composes debug only`() {
        val f = fixture("today")
        f.build(
            "checkDebugMergedManifest",
            "checkDebugCameraClasspath",
            "checkReleaseMergedManifest",
            "checkBenchmarkMergedManifest",
            "checkReleaseCameraClasspath",
            "checkBenchmarkCameraClasspath",
        )
        listOf("debug", "release", "benchmark").forEach { variant ->
            val manifest =
                f.projectDir
                    .resolve(
                        "build/intermediates/merged_manifest/$variant/" +
                            "process${variant.replaceFirstChar { it.uppercase() }}MainManifest/AndroidManifest.xml",
                    ).readText()
            assertTrue(("android.permission.CAMERA" in manifest) == (variant == "debug"), manifest)
            assertTrue(("android.hardware.camera" in manifest) == (variant == "debug"), manifest)
        }
        val debug = f.build("dependencies", "--configuration", "debugRuntimeClasspath").output
        assertTrue("androidx.camera:camera-camera2:1.0" in debug, debug)
        val release = f.build("dependencies", "--configuration", "releaseRuntimeClasspath").output
        assertTrue("androidx.camera:" !in release, release)
    }

    @Test
    fun `OFF rejects stray permission and camera dependency`() {
        val f = fixture("stray")
        f.write(
            "src/release/AndroidManifest.xml",
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<uses-permission android:name="android.permission.CAMERA"/>
</manifest>""",
        )
        assertTrue("android.permission.CAMERA" in f.buildAndFail("checkReleaseMergedManifest").output)
        append(f, """dependencies { "releaseImplementation"("androidx.camera:camera-camera2:1.0") }""")
        assertTrue("androidx.camera:camera-camera2:1.0" in f.buildAndFail("checkReleaseCameraClasspath").output)
    }

    @Test
    fun `raised release composes camera and privacy facts`() {
        val f = fixture("raised")
        append(
            f,
            """
            cubentiaFeatureFlags {
                byBuildType = byBuildType + ("release" to (byBuildType.getValue("release") + ("CAMERA_SCAN" to "BETA")))
            }
            """.trimIndent(),
        )
        f.build("checkReleaseMergedManifest", "checkReleaseCameraClasspath", "writePrivacyFacts")
        val manifest =
            f.projectDir
                .resolve("build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml")
                .readText()
        assertTrue("android.permission.CAMERA" in manifest && "android.hardware.camera.any" in manifest, manifest)
        val facts = f.projectDir.resolve("docs/privacy-facts.json").readText()
        assertTrue(Regex("\"runtimePermissions\":\\s*\\[\\s*\"android.permission.CAMERA\"\\s*]").containsMatchIn(facts), facts)
    }

    @Test
    fun `dependent flags without CAMERA_SCAN fail configuration`() {
        listOf("PLACEMENT", "QUICK_SCAN", "CASE_RECOGNITION").forEach { flag ->
            val f = fixture("invalid-$flag")
            append(
                f,
                """
                cubentiaFeatureFlags {
                    byBuildType = byBuildType + ("release" to (byBuildType.getValue("release") + ("$flag" to "BETA")))
                }
                """.trimIndent(),
            )
            val output = f.buildAndFail("help").output
            assertTrue(flag in output && "CAMERA_SCAN" in output, output)
        }
    }
}
