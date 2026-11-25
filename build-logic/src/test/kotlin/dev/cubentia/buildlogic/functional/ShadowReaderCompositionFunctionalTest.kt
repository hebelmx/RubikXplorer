package dev.cubentia.buildlogic.functional

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Story 10.23 / Story 10.31: the debug shadow sticker reader is retired (no variant composes `src/camera/debug/sticker/`),
 * while `check*ShadowReader` remains as a non-debuggable release guard against any `sticker_reader*.tflite` asset or
 * `dev/cubentia/app/camera/sticker/` class (allowing `sticker_centers_v2_int8.tflite`).
 */
class ShadowReaderCompositionFunctionalTest {
    private fun fixture(name: String) =
        AndroidFixture("shadow-$name", plugins = listOf("cubentia.release", "cubentia.quality")).also {
            it.write("src/camera/debug/sticker/assets/sticker_reader.tflite", "retired model")
            it.write(
                "src/camera/debug/sticker/AndroidManifest.xml",
                """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<application><receiver android:name="dev.cubentia.fixture.ShadowReaderToggleReceiver" android:exported="false"/></application>
</manifest>""",
            )
            it.write("src/camera/continuous/assets/sticker_centers_v2_int8.tflite", "continuous model")
            it.write("src/camera/corner/assets/corner_keypoints.tflite", "corner model")
        }

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
    fun `neither debug nor release composes the retired sticker folder and continuous model passes guard`() {
        val f = fixture("retired")
        f.build(
            "mergeDebugAssets",
            "checkDebugMergedManifest",
            "checkReleaseMergedManifest",
            "checkReleaseShadowReader",
            "checkBenchmarkShadowReader",
            "-Pcubentia.release011=true",
            "checkRelease011ShadowReader",
            "-Pcubentia.cornerR8=true",
            "checkCornerR8ShadowReader",
        )
        assertTrue("ShadowReaderToggleReceiver" !in manifest(f, "debug"), manifest(f, "debug"))
        assertTrue("sticker_reader.tflite" !in mergedAssets(f, "debug"), mergedAssets(f, "debug").toString())
        assertTrue("sticker_centers_v2_int8.tflite" in mergedAssets(f, "debug"), mergedAssets(f, "debug").toString())
        assertTrue("ShadowReaderToggleReceiver" !in manifest(f, "release"), manifest(f, "release"))
        assertTrue("sticker_reader.tflite" !in mergedAssets(f, "release"), mergedAssets(f, "release").toString())
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
