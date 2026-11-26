package dev.cubentia.buildlogic.functional

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Story 10.34 (AD-9 Frames): `check<Variant>FixtureWriter` passes every non-debuggable variant of the tree and fails one
 * carrying a class of the debug-only continuous-session writer package `dev/cubentia/app/camera/recorder/session/`.
 */
class FixtureWriterCompositionFunctionalTest {
    private fun fixture(name: String) = AndroidFixture("fixture-writer-$name", plugins = listOf("cubentia.release", "cubentia.quality"))

    @Test
    fun `release, benchmark, release011 and cornerR8 pass and check runs it`() {
        val f = fixture("pass")
        f.build(
            "checkReleaseFixtureWriter",
            "checkBenchmarkFixtureWriter",
            "-Pcubentia.release011=true",
            "checkRelease011FixtureWriter",
            "-Pcubentia.cornerR8=true",
            "checkCornerR8FixtureWriter",
        )
        val plan = f.build("check", "--dry-run").output
        assertTrue(":checkReleaseFixtureWriter " in plan, plan)
        assertTrue(":checkBenchmarkFixtureWriter " in plan, plan)
        assertTrue(":checkDebugFixtureWriter" !in plan, plan)
    }

    @Test
    fun `the check fails on a planted release writer class`() {
        val f = fixture("class")
        f.write(
            "src/release/java/dev/cubentia/app/camera/recorder/session/Planted.java",
            "package dev.cubentia.app.camera.recorder.session;\npublic class Planted {}\n",
        )
        val output = f.buildAndFail("checkReleaseFixtureWriter").output
        assertTrue("fixture-writer class dev/cubentia/app/camera/recorder/session/Planted.class" in output, output)
    }
}
