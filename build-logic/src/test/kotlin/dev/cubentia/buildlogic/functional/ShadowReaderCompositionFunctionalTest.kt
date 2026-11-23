package dev.cubentia.buildlogic.functional

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Story 10.23 (AC7): the shadow sticker reader composes only into debuggable corner-model builds, and the check proves it. */
class ShadowReaderCompositionFunctionalTest {
    private fun fixture(name: String) =
        AndroidFixture("shadow-$name", plugins = listOf("cubentia.release", "cubentia.quality")).also {
            it.write("src/camera/debug/sticker/assets/sticker_reader.tflite", "not a model")
        }

    private fun append(
        fixture: AndroidFixture,
        script: String,
    ) = fixture.write("build.gradle.kts", fixture.projectDir.resolve("build.gradle.kts").readText() + "\n" + script)

    private fun manifest(
        fixture: AndroidFixture,
        variant: String,
    ): String =
        fixture.projectDir
            .resolve(
                "build/intermediates/merged_manifest/$variant/process${variant.replaceFirstChar {
                    it.uppercase()
                }}MainManifest/AndroidManifest.xml",
            ).readText()

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

    @Test
    fun `debug composes the sticker folder and release does not`() {
        val f = fixture("today")
        f.build(
            "mergeDebugAssets",
            "checkDebugMergedManifest",
            "checkReleaseMergedManifest",
            "checkReleaseShadowReader",
            "checkBenchmarkShadowReader",
        )
        assertTrue("ShadowReaderToggleReceiver" in manifest(f, "debug"), manifest(f, "debug"))
        assertTrue("sticker_reader.tflite" in mergedAssets(f, "debug"), mergedAssets(f, "debug").toString())
        assertTrue("ShadowReaderToggleReceiver" !in manifest(f, "release"), manifest(f, "release"))
        assertTrue("sticker_reader.tflite" !in mergedAssets(f, "release"), mergedAssets(f, "release").toString())
    }

    @Test
    fun `debuggable without a corner-model flag does not compose it`() {
        val f = fixture("no-corner")
        append(
            f,
            """
            cubentiaFeatureFlags {
                byBuildType = byBuildType + ("debug" to (byBuildType.getValue("debug") + ("QUICK_SCAN" to "OFF") + ("CASE_RECOGNITION" to "OFF")))
            }
            """.trimIndent(),
        )
        f.build("mergeDebugAssets", "checkDebugMergedManifest")
        val debug = manifest(f, "debug")
        assertTrue("android.permission.CAMERA" in debug && "ShadowReaderToggleReceiver" !in debug, debug)
        assertTrue("sticker_reader.tflite" !in mergedAssets(f, "debug"), mergedAssets(f, "debug").toString())
    }

    @Test
    fun `the check fails on a planted release asset`() {
        val f = fixture("asset")
        f.write("src/release/assets/sticker_reader_v2.tflite", "planted")
        val output = f.buildAndFail("checkReleaseShadowReader").output
        assertTrue("sticker model asset sticker_reader_v2.tflite" in output, output)
    }

    @Test
    fun `the check fails on a planted release class`() {
        val f = fixture("class")
        f.write(
            "src/release/java/dev/cubentia/app/camera/sticker/Planted.java",
            "package dev.cubentia.app.camera.sticker;\npublic class Planted {}\n",
        )
        val output = f.buildAndFail("checkReleaseShadowReader").output
        assertTrue("shadow-reader class dev/cubentia/app/camera/sticker/Planted.class" in output, output)
    }
}
