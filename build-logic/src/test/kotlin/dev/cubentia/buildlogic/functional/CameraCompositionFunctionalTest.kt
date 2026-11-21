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
            "writePrivacyFacts",
        )
        listOf("debug", "release", "benchmark").forEach { variant ->
            val manifest =
                f.projectDir
                    .resolve(
                        "build/intermediates/merged_manifest/$variant/" +
                            "process${variant.replaceFirstChar { it.uppercase() }}MainManifest/AndroidManifest.xml",
                    ).readText()
            assertTrue(("android.permission.CAMERA" in manifest) == (variant == "debug"), manifest)
            assertTrue(("FixtureRecorderActivity" in manifest) == (variant == "debug"), manifest)
            assertTrue(("android.hardware.camera" in manifest) == (variant == "debug"), manifest)
        }
        val debug = f.build("dependencies", "--configuration", "debugRuntimeClasspath").output
        assertTrue("androidx.camera:camera-camera2:1.0" in debug, debug)
        assertTrue("com.google.ai.edge.litert:litert:1.0" in debug, debug)
        val release = f.build("dependencies", "--configuration", "releaseRuntimeClasspath").output
        assertTrue("androidx.camera:" !in release, release)
        assertTrue("com.google.ai.edge.litert:" !in release, release)
        f.build("checkDebugElfAlignment", "checkReleaseElfAlignment")
        val facts = f.projectDir.resolve("docs/privacy-facts.json").readText()
        assertTrue("\"schemaVersion\": 1," in facts && "cameraFramesLeaveDevice" !in facts, facts)
    }

    @Test
    fun `debuggable camera OFF has no recorder`() {
        val f = fixture("debug-off")
        append(
            f,
            """
            cubentiaFeatureFlags {
                byBuildType = byBuildType + ("debug" to byBuildType.getValue("release"))
            }
            """.trimIndent(),
        )
        f.build("checkDebugMergedManifest", "checkDebugCameraClasspath")
        val manifest =
            f.projectDir
                .resolve(
                    "build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
                ).readText()
        assertTrue("FixtureRecorderActivity" !in manifest && "android.permission.CAMERA" !in manifest, manifest)
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
        assertTrue("FixtureRecorderActivity" !in manifest, manifest)
        val facts = f.projectDir.resolve("docs/privacy-facts.json").readText()
        assertTrue(Regex("\"runtimePermissions\":\\s*\\[\\s*\"android.permission.CAMERA\"\\s*]").containsMatchIn(facts), facts)
        assertTrue("\"schemaVersion\": 2," in facts && "\"cameraFramesLeaveDevice\": false," in facts, facts)
        val releaseDeps = f.build("dependencies", "--configuration", "releaseRuntimeClasspath").output
        assertTrue("androidx.camera:camera-camera2:1.0" in releaseDeps, releaseDeps)
        assertTrue("com.google.ai.edge.litert:" !in releaseDeps, releaseDeps)
    }

    @Test
    fun `corner-flag release composes LiteRT and ELF alignment gate catches 4 KB native libs`() {
        val f = fixture("corner-release")
        append(
            f,
            """
            cubentiaFeatureFlags {
                byBuildType = byBuildType + ("release" to (byBuildType.getValue("release") + ("CAMERA_SCAN" to "BETA") + ("QUICK_SCAN" to "BETA")))
            }
            """.trimIndent(),
        )
        f.build("checkReleaseMergedManifest", "checkReleaseCameraClasspath", "checkReleaseElfAlignment")
        val releaseDeps = f.build("dependencies", "--configuration", "releaseRuntimeClasspath").output
        assertTrue("com.google.ai.edge.litert:litert:1.0" in releaseDeps, releaseDeps)

        // Replace LiteRT with a 4 KB aligned .so and verify checkReleaseElfAlignment fails loudly.
        f.publishAar(
            "com.google.ai.edge.litert",
            "litert",
            mapOf(
                "jni/arm64-v8a/libLiteRt.so" to
                    dev.cubentia.buildlogic.policy.ElfAlignmentPolicyTest
                        .elf64(listOf(0x1000L)),
            ),
        )
        val fail = f.buildAndFail("checkReleaseElfAlignment").output
        assertTrue("0x1000" in fail && "libLiteRt.so" in fail, fail)
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
