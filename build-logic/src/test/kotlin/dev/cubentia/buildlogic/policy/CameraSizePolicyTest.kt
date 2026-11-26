package dev.cubentia.buildlogic.policy

import dev.cubentia.buildlogic.tasks.CheckCameraSizeTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CameraSizePolicyTest {
    @Test
    fun estimateIncludesThreeCopiesOfClassesAndOneOfRuntime() {
        val verdict = CameraSizePolicy.evaluate(20_000_000, 100_000, 500_000, listOf("camera-core.aar"))
        assertEquals(20_800_000L, verdict.estimatedBytes)
        assertTrue(verdict.passes)
    }

    @Test
    fun budgetIsExclusiveAndEveryForbiddenArtifactIsReported() {
        assertFalse(CameraSizePolicy.evaluate(29_999_996, 1, 1, emptyList()).passes)
        val verdict =
            CameraSizePolicy.evaluate(
                20_000_000,
                1,
                1,
                listOf("opencv.aar", "tensorflow-lite.aar", "litert.jar", "camera-core.aar", "litert.jar"),
            )
        assertFalse(verdict.passes)
        assertEquals(listOf("litert.jar", "opencv.aar", "tensorflow-lite.aar"), verdict.forbidden)
    }

    @Test
    fun allowLiteRtPermitsLiteRtAndCountsAssetsWhileStillRejectingOpenCv() {
        val allowed =
            CameraSizePolicy.evaluate(
                releaseEstimate = 12_000_000,
                classBytes = 100_000,
                runtimeBytes = 5_700_000,
                artifacts = listOf("litert-2.3.2.aar", "camera-core.aar"),
                allowLiteRt = true,
                assetBytes = 300_000,
            )
        assertTrue(allowed.passes)
        assertEquals(18_300_000L, allowed.estimatedBytes)
        assertTrue(allowed.forbidden.isEmpty())

        val rejected =
            CameraSizePolicy.evaluate(
                releaseEstimate = 12_000_000,
                classBytes = 1,
                runtimeBytes = 1,
                artifacts = listOf("litert-2.3.2.aar", "opencv-4.12.0.aar", "tensorflow-lite.aar"),
                allowLiteRt = true,
            )
        assertFalse(rejected.passes)
        assertEquals(listOf("opencv-4.12.0.aar", "tensorflow-lite.aar"), rejected.forbidden)
    }

    @Test
    fun artifactRuntimeBytesPrefersArm64NativeAndAppliesR8JarEstimate(
        @TempDir tempDir: File,
    ) {
        val aar = File(tempDir, "litert.aar")
        ZipOutputStream(aar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.jar"))
            zip.write(ByteArray(100_000))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("jni/arm64-v8a/libLiteRt.so"))
            zip.write(ByteArray(5_000_000))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("jni/x86_64/libLiteRt.so"))
            zip.write(ByteArray(7_500_000))
            zip.closeEntry()
        }
        // Expect 55% (measured cornerR8 factor) of 100 KB jar + 100% of 5 MB arm64-v8a (NOT 7.5 MB x86_64).
        assertEquals(5_055_000L, CheckCameraSizeTask.artifactRuntimeBytes(aar))
    }

    @Test
    fun legacyCornerAndDebugOnlyGlobsAreExcludedFrom011Projection() {
        assertEquals(
            listOf("dev/cubentia/app/camera/sticker/**"),
            CameraSizePolicy.debugOnlyClassGlobs,
        )
        assertEquals(
            listOf("dev/cubentia/app/camera/corner/**"),
            CameraSizePolicy.legacyCornerClassGlobs,
        )
    }
}
