package dev.cubentia.buildlogic.functional

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** ai-b3 (#119): PR CI compiles androidTest sources and guards shell-script line endings. */
class CiWorkflowTest {
    private val workflow = File("../.github/workflows/ci.yml").readText().replace("\r\n", "\n")

    private val onBlock = workflow.substringAfter("\non:\n").substringBefore("\n\n")

    /** The `build` job: from its key to the next two-space-indented job key. */
    private val buildJob =
        Regex("""(?ms)^  build:\n(.*?)(?=^  \S|\z)""").find(workflow)?.groupValues?.get(1)
            ?: error("ci.yml has no build job")

    private val runs =
        Regex("""(?m)^\s*(?:-\s+)?run:\s*(.+)$""").findAll(buildJob).map { it.groupValues[1] }.toList()

    @Test
    fun `ci triggers on pull requests`() {
        assertTrue(Regex("""(?m)^  pull_request:""").containsMatchIn(onBlock), onBlock)
    }

    @Test
    fun `build job compiles androidTest Kotlin`() {
        assertTrue(runs.any { it.contains(":app:compileDebugAndroidTestKotlin") }, runs.toString())
    }

    @Test
    fun `build job runs the shell-script LF guard`() {
        assertTrue(runs.any { it.contains(".github/scripts/check-sh-eol.sh") }, runs.toString())
    }

    @Test
    fun `build job assembles the cornerR8 build type and its androidTest APK`() {
        assertTrue(
            runs.any {
                it.contains("-Pcubentia.cornerR8=true") && it.contains(":app:assembleCornerR8 ") &&
                    it.contains(":app:assembleCornerR8AndroidTest") && !it.contains("connected")
            },
            runs.toString(),
        )
    }

    /** Story 10.34: the fixture-writer guards and the fixture size gate run on every PR. */
    @Test
    fun `build job runs the fixture-writer guards and the fixture size gate`() {
        val gate = steps.single { it.contains("Verify build wires the gates") }
        listOf(":app:checkReleaseFixtureWriter", ":app:checkBenchmarkFixtureWriter", ":checkFixtureSize").forEach { task ->
            assertTrue(gate.contains(task), "dry-run gate list lacks $task")
        }
        assertTrue(
            runs.any { it.contains("-Pcubentia.cornerR8=true") && it.contains(":app:checkCornerR8FixtureWriter") },
            runs.toString(),
        )
        assertTrue(
            runs.any { it.contains("-Pcubentia.release011=true") && it.contains(":app:checkRelease011FixtureWriter") },
            runs.toString(),
        )
    }

    /** Each step of the build job, from its `- ` line to the next one. */
    private val steps = buildJob.split(Regex("""(?m)^(?=      - )""")).filter { it.startsWith("      - ") }

    @Test
    fun `the new gates can neither be skipped nor ignored`() {
        listOf(
            ":app:compileDebugAndroidTestKotlin",
            "check-sh-eol.sh",
            ":app:assembleCornerR8AndroidTest",
            ":app:checkCornerR8FixtureWriter",
            ":app:checkRelease011FixtureWriter",
        ).forEach { marker ->
            val step = steps.singleOrNull { it.contains(marker) } ?: error("no single build step runs $marker")
            assertTrue(!step.contains("continue-on-error"), step)
            assertTrue(!Regex("""(?m)^\s+if:""").containsMatchIn(step), step)
        }
    }
}
