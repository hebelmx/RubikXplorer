package dev.cubentia.buildlogic.functional

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Story 7.8: the release workflow's tag and Play-status guard (`.github/scripts/validate-release-inputs.sh`). */
class ValidateReleaseInputsScriptTest {
    private val script = File("../.github/scripts/validate-release-inputs.sh")

    private fun run(vararg args: String): Pair<Int, String> {
        val process =
            ProcessBuilder(listOf("bash", script.path) + args)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "script timed out" }
        return process.exitValue() to output
    }

    @Test
    fun `a vX_Y_Z tag with draft or completed yields version and status`() {
        assertEquals(0 to "version=1.2.3\nstatus=draft\n", run("tag", "v1.2.3", "draft"))
        assertEquals(0 to "version=10.0.42\nstatus=completed\n", run("tag", "v10.0.42", "completed"))
    }

    @Test
    fun `a branch, a malformed tag or an unknown status fails before upload`() {
        listOf(
            listOf("branch", "v1.2.3", "draft"),
            listOf("tag", "1.2.3", "draft"),
            listOf("tag", "v1.2", "draft"),
            listOf("tag", "v1.2.3-rc1", "draft"),
            listOf("tag", "v1.2.3", "inProgress"),
            listOf("tag", "v1.2.3", ""),
        ).forEach { args ->
            val (exit, output) = run(*args.toTypedArray())
            assertNotEquals(0, exit, "$args -> $output")
        }
    }
}
