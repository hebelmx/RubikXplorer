package dev.cubentia.buildlogic.tasks

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CheckLearnerTextTaskTest {
    @TempDir lateinit var projectDir: File

    @Test fun `new resource fails the registered task`() {
        projectDir.resolve("learner-text-baseline.txt").writeText("existing\n")
        projectDir.resolve("src/main/res/values/strings.xml").apply {
            parentFile.mkdirs()
            writeText("""<resources><string name="unexpected">New text</string></resources>""")
        }
        projectDir.resolve("src/debug/res/values/messages.xml").apply {
            parentFile.mkdirs()
            writeText("""<resources><string name="debug_only">Other text</string></resources>""")
        }
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val task = CheckLearnerTextTask.register(project).get()
        task.report
            .get()
            .asFile.parentFile
            .mkdirs()
        val failure = assertThrows(GradleException::class.java) { task.check() }
        assertTrue(failure.message.orEmpty().contains("strings.xml:1"))
        assertTrue(failure.message.orEmpty().contains("unexpected"))
        assertTrue(failure.message.orEmpty().contains("src/debug/res/values/messages.xml:1"))
        assertTrue(failure.message.orEmpty().contains("debug_only"))
    }
}
