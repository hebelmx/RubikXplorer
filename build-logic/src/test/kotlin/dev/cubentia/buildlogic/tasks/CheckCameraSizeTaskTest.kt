package dev.cubentia.buildlogic.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CheckCameraSizeTaskTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `artifactRuntimeBytes counts R8-estimated jars plus the arm64-v8a native library set`() {
        val aar =
            tempDir.resolve("litert-2.3.2.aar").apply {
                ZipOutputStream(outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("classes.jar"))
                    zip.write(ByteArray(25_000))
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("jni/arm64-v8a/libLiteRt.so"))
                    zip.write(ByteArray(5_600_000))
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("jni/armeabi-v7a/libLiteRt.so"))
                    zip.write(ByteArray(4_200_000))
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("jni/x86_64/libLiteRt.so"))
                    zip.write(ByteArray(5_100_000))
                    zip.closeEntry()
                }
            }
        // 55% R8 retention of the 25 KB jar + 100% of the Play-delivered arm64-v8a .so (not summed across ABIs).
        assertEquals(5_613_750L, CheckCameraSizeTask.artifactRuntimeBytes(aar))
    }

    @Test
    fun `the jar R8 factor is not below the measured cornerR8 retention`() {
        // 2026-11-21 cornerR8 vs release classes.dex: 5,071,618 B retained of ~10,002,505 B camera jar bytes (50.7%).
        val measuredRetained = 5_071_618L
        val measuredJarBytes = 10_002_505L
        assertTrue(
            measuredJarBytes * CheckCameraSizeTask.UNMINIFIED_JAR_R8_NUMERATOR / CheckCameraSizeTask.UNMINIFIED_JAR_R8_DENOMINATOR >=
                measuredRetained,
        )
    }
}
