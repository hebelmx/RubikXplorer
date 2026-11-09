package dev.cubentia.buildlogic.tasks

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CheckMergedManifestTaskTest {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun `an unset debuggable fails the action instead of defaulting`() {
        val manifest =
            projectDir.resolve("AndroidManifest.xml").apply {
                writeText(
                    """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                        <application android:allowBackup="false" />
                    </manifest>
                    """.trimIndent(),
                )
            }
        val project =
            ProjectBuilder
                .builder()
                .withProjectDir(projectDir)
                .build()
        val task = project.tasks.register("checkManifest", CheckMergedManifestTask::class.java).get()
        task.variantName.set("custom")
        task.mergedManifest.set(manifest)
        task.report.set(projectDir.resolve("report.txt"))

        val failure = assertThrows(IllegalStateException::class.java) { task.check() }

        assertTrue("debuggable" in failure.message.orEmpty(), failure.message)
    }
}
