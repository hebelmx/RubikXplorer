package dev.cubentia.buildlogic.tasks

import dev.cubentia.buildlogic.FixtureConventionPlugin
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile

/** Story 10.34: `:checkFixtureSize` at the root, in `check`, failing on an oversized session or root. */
class CheckFixtureSizeTaskTest {
    @TempDir
    lateinit var projectDir: File

    private fun sparse(
        path: String,
        bytes: Long,
    ) = File(projectDir, path).apply {
        parentFile.mkdirs()
        RandomAccessFile(this, "rw").use { it.setLength(bytes) }
    }

    private fun task(): CheckFixtureSizeTask {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.pluginManager.apply(FixtureConventionPlugin::class.java)
        val check = project.tasks.getByName("check")
        val task = project.tasks.getByName("checkFixtureSize") as CheckFixtureSizeTask
        assertTrue(check.taskDependencies.getDependencies(check).contains(task))
        return task
    }

    @Test
    fun `a missing root and small sessions pass`() {
        task().check()
        sparse("Fixtures/continuous/s1/frames.jsonl", 1_000)
        sparse("Fixtures/continuous/s2/frames.jsonl", 15_000_000)
        task().check()
        assertTrue(File(projectDir, "build/reports/cubentia/fixture-size.txt").readText().contains("total 15001000"))
    }

    @Test
    fun `an oversized session fails naming it and its bytes`() {
        sparse("Fixtures/continuous/big/images/frame-00000.png", 14_999_999)
        sparse("Fixtures/continuous/big/manifest.json", 2)
        val failure = assertThrows(GradleException::class.java) { task().check() }
        assertTrue("Fixtures/continuous/big is 15000001 B" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `an oversized tmp dir or a dir without a manifest is not a session, but still counts in the total`() {
        sparse("Fixtures/continuous/.tmp-x/images/frame-00000.png", 15_000_001)
        sparse("Fixtures/continuous/.tmp-x/manifest.json", 2)
        sparse("Fixtures/continuous/loose/frames.jsonl", 15_000_001)
        task().check()
        val report = File(projectDir, "build/reports/cubentia/fixture-size.txt").readText()
        assertTrue(".tmp-x" !in report && "loose" !in report, report)
        assertTrue(report.contains("total 30000004"), report)
    }

    @Test
    fun `an oversized root fails`() {
        (1..5).forEach { sparse("Fixtures/continuous/s$it/frames.jsonl", 12_000_001) }
        val failure = assertThrows(GradleException::class.java) { task().check() }
        assertTrue("Fixtures/continuous/ is 60000005 B" in failure.message.orEmpty(), failure.message)
    }
}
