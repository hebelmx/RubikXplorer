package dev.cubentia.buildlogic.functional

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class CameraCompositionFunctionalTest {
    /** [AndroidFixture] writes `src/camera/AndroidManifest.xml` and the fake CameraX/LiteRT catalog modules. */
    private fun fixture(name: String) =
        AndroidFixture("camera-$name", plugins = listOf("cubentia.release", "cubentia.quality")).also {
            it.write("src/camera/continuous/assets/sticker_centers_v2_int8.tflite", "continuous-model-bytes")
            it.write("src/camera/corner/assets/corner_keypoints.tflite", "corner-model-bytes-legacy")
        }

    private fun mergedAssets(
        fixture: AndroidFixture,
        variant: String,
    ): List<String> =
        fixture.projectDir
            .resolve("build/intermediates/assets/$variant")
            .walkTopDown()
            .filter(File::isFile)
            .map { it.name }
            .toList()

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
    fun `raised guided-only release composes camera without either tflite or LiteRT`() {
        val f = fixture("raised")
        append(
            f,
            """
            cubentiaFeatureFlags {
                byBuildType = byBuildType + ("release" to (byBuildType.getValue("release") + ("CAMERA_SCAN" to "GA")))
            }
            """.trimIndent(),
        )
        f.build("mergeReleaseAssets", "checkReleaseMergedManifest", "checkReleaseCameraClasspath", "writePrivacyFacts")
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
        val assets = mergedAssets(f, "release")
        assertFalse("sticker_centers_v2_int8.tflite" in assets, assets.toString())
        assertFalse("corner_keypoints.tflite" in assets, assets.toString())
    }

    @Test
    fun `011 continuous-only profile ships sticker_centers without corner_keypoints and checks ELF alignment`() {
        val f = fixture("continuous-only")
        // Without -Pcubentia.release011=true, the release011 build type and its tasks are absent from default builds.
        val missing = f.buildAndFail("checkRelease011Size").output
        assertTrue("checkRelease011Size" in missing, missing)

        f.build(
            "-Pcubentia.release011=true",
            "mergeRelease011Assets",
            "checkRelease011MergedManifest",
            "checkRelease011CameraClasspath",
            "checkRelease011ShadowReader",
            "checkRelease011ElfAlignment",
        )
        val mergedManifest =
            f.projectDir
                .resolve("build/intermediates/merged_manifest/release011/processRelease011MainManifest/AndroidManifest.xml")
                .readText()
        assertFalse("FOREGROUND_SERVICE" in mergedManifest, mergedManifest)
        val deps = f.build("-Pcubentia.release011=true", "dependencies", "--configuration", "release011RuntimeClasspath").output
        assertTrue("com.google.ai.edge.litert:litert:1.0" in deps, deps)
        val assets = mergedAssets(f, "release011")
        assertTrue("sticker_centers_v2_int8.tflite" in assets, assets.toString())
        assertFalse("corner_keypoints.tflite" in assets, assets.toString())
        assertFalse(assets.any { it.startsWith("sticker_reader") }, assets.toString())

        // Replace LiteRT with a 4 KB aligned .so and verify checkRelease011ElfAlignment fails loudly.
        f.publishAar(
            "com.google.ai.edge.litert",
            "litert",
            entries =
                mapOf(
                    "jni/arm64-v8a/libLiteRt.so" to
                        dev.cubentia.buildlogic.policy.ElfAlignmentPolicyTest
                            .elf64(listOf(0x1000L)),
                ),
            manifestPermissions =
                listOf(
                    "android.permission.FOREGROUND_SERVICE",
                    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                ),
        )
        val fail = f.buildAndFail("-Pcubentia.release011=true", "checkRelease011ElfAlignment").output
        assertTrue("0x1000" in fail && "libLiteRt.so" in fail, fail)
    }

    @Test
    fun `CASE_RECOGNITION alone ships corner_keypoints without sticker_centers`() {
        val f = fixture("case-only")
        append(
            f,
            """
            cubentiaFeatureFlags {
                byBuildType = byBuildType + ("release" to (byBuildType.getValue("release") + ("CAMERA_SCAN" to "BETA") + ("CASE_RECOGNITION" to "BETA")))
            }
            """.trimIndent(),
        )
        f.build("mergeReleaseAssets", "checkReleaseMergedManifest", "checkReleaseCameraClasspath", "checkReleaseShadowReader")
        val deps = f.build("dependencies", "--configuration", "releaseRuntimeClasspath").output
        assertTrue("com.google.ai.edge.litert:litert:1.0" in deps, deps)
        val assets = mergedAssets(f, "release")
        assertTrue("corner_keypoints.tflite" in assets, assets.toString())
        assertFalse("sticker_centers_v2_int8.tflite" in assets, assets.toString())
    }

    @Test
    fun `cornerR8 composes both continuous and legacy corner models`() {
        val f = fixture("corner-r8")
        f.build(
            "-Pcubentia.cornerR8=true",
            "mergeCornerR8Assets",
            "checkCornerR8MergedManifest",
            "checkCornerR8CameraClasspath",
            "checkCornerR8ShadowReader",
        )
        val assets = mergedAssets(f, "cornerR8")
        assertTrue("sticker_centers_v2_int8.tflite" in assets, assets.toString())
        assertTrue("corner_keypoints.tflite" in assets, assets.toString())
    }

    @Test
    fun `checkCameraSize excludes corner assets and classes and avoids double-counting when release enables continuous`() {
        val f = fixture("camera-size-no-double-count")
        f.write("src/camera/assets/shared.bin", "12345")
        f.write("src/camera/kotlin/dev/cubentia/app/camera/SharedCam.kt", "package dev.cubentia.app.camera\nclass SharedCam\n")
        f.write(
            "src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContDet.kt",
            "package dev.cubentia.app.camera.continuous\nclass ContDet\n",
        )
        f.write(
            "src/camera/corner/kotlin/dev/cubentia/app/camera/corner/LegacyCorner.kt",
            "package dev.cubentia.app.camera.corner\nclass LegacyCorner\n",
        )
        append(
            f,
            """
            tasks.register("inspectCameraSize") {
                dependsOn("compileDebugKotlin")
                doLast {
                    val t = tasks.named("checkCameraSize", dev.cubentia.buildlogic.tasks.CheckCameraSizeTask::class.java).get()
                    val assets = t.cameraAssets.files.filter { it.isFile }.map { it.name }.sorted()
                    val classes = t.cameraClasses.files.filter { it.isFile }.map { it.name }.sorted()
                    println("CAMERA_SIZE_ASSETS=" + assets.joinToString(","))
                    println("CAMERA_SIZE_CLASSES=" + classes.joinToString(","))
                }
            }
            """.trimIndent(),
        )
        val offOut = f.build("inspectCameraSize").output
        assertTrue("CAMERA_SIZE_ASSETS=shared.bin,sticker_centers_v2_int8.tflite" in offOut, offOut)
        assertTrue("CAMERA_SIZE_CLASSES=ContDet.class,SharedCam.class" in offOut, offOut)
        assertFalse("LegacyCorner.class" in offOut, offOut)

        // Intermediate branch: release already ships Guided camera (CAMERA_SCAN=GA) while QUICK_SCAN=OFF.
        val baseBuild = f.projectDir.resolve("build.gradle.kts").readText()
        f.write(
            "build.gradle.kts",
            baseBuild +
                "\n" +
                """
                cubentiaFeatureFlags {
                    byBuildType = byBuildType + ("release" to (byBuildType.getValue("release") + ("CAMERA_SCAN" to "GA")))
                }
                """.trimIndent(),
        )
        val guidedOut = f.build("inspectCameraSize").output
        assertTrue("CAMERA_SIZE_ASSETS=sticker_centers_v2_int8.tflite" in guidedOut, guidedOut)
        assertFalse("shared.bin" in guidedOut, guidedOut)
        assertTrue("CAMERA_SIZE_CLASSES=ContDet.class" in guidedOut, guidedOut)
        assertFalse("SharedCam.class" in guidedOut, guidedOut)
        assertFalse("LegacyCorner.class" in guidedOut, guidedOut)

        // Final branch: release ships both Guided camera and continuous Quick Scan (zero double-counting).
        f.write(
            "build.gradle.kts",
            baseBuild +
                "\n" +
                """
                cubentiaFeatureFlags {
                    byBuildType = byBuildType + ("release" to (byBuildType.getValue("release") + ("CAMERA_SCAN" to "GA") + ("QUICK_SCAN" to "BETA")))
                }
                """.trimIndent(),
        )
        val onOut = f.build("inspectCameraSize").output
        assertTrue("CAMERA_SIZE_ASSETS=" in onOut && "sticker_centers_v2_int8.tflite" !in onOut && "shared.bin" !in onOut, onOut)
        assertEquals(true, "CAMERA_SIZE_CLASSES=\n" in onOut.replace("\r\n", "\n"), onOut)
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
