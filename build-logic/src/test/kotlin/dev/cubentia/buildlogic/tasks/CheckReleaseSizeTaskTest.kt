package dev.cubentia.buildlogic.tasks

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Story 7.8 (NFR-11, AD-14): the task writes the installed-size report and fails the build above budget. */
class CheckReleaseSizeTaskTest {
    @TempDir
    lateinit var projectDir: File

    private fun apk(dexBytes: Int): File =
        projectDir.resolve("universal.apk").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(ByteArray(dexBytes))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("classes2.dex"))
                zip.write(ByteArray(dexBytes))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("assets/notdex.bin"))
                zip.write(ByteArray(dexBytes))
                zip.closeEntry()
            }
        }

    private fun task(dexBytes: Int): CheckReleaseSizeTask {
        val project =
            ProjectBuilder
                .builder()
                .withProjectDir(projectDir)
                .build()
        return project.tasks.register("checkReleaseSize", CheckReleaseSizeTask::class.java).get().apply {
            apk.set(apk(dexBytes))
            bundle.set(projectDir.resolve("app.aab").apply { writeBytes(ByteArray(1234)) })
            report.set(projectDir.resolve("reports/release-size.txt"))
        }
    }

    @Test
    fun `an APK under budget writes the report and passes`() {
        task(dexBytes = 1_000).check()

        val report = projectDir.resolve("reports/release-size.txt").readText()
        assertTrue(report.contains("AAB: 1234"), report)
        assertTrue(report.contains("Uncompressed dex: 2000"), report)
        assertTrue(report.contains("Budget: 30000000"), report)
    }

    @Test
    fun `an estimate over budget fails the build after writing the report`() {
        // Two 5,000,000-byte dex entries: 3 x 10,000,000 alone exceeds 30,000,000.
        val failure = assertThrows(GradleException::class.java) { task(dexBytes = 5_000_000).check() }

        assertTrue(failure.message.orEmpty().contains("30000000"), failure.message)
        assertTrue(projectDir.resolve("reports/release-size.txt").readText().contains("Headroom: -"))
    }
}
