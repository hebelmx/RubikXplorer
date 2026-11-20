package dev.cubentia.buildlogic.functional

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/** ai-b3 (#119): the PR CI guard that tracked `*.sh` files keep `eol=lf` (`.github/scripts/check-sh-eol.sh`). */
class CheckShEolScriptTest {
    private val script = File("../.github/scripts/check-sh-eol.sh").absoluteFile

    @TempDir
    lateinit var temp: File

    private fun exec(
        dir: File,
        vararg command: String,
    ): Pair<Int, String> {
        val process =
            ProcessBuilder(command.toList())
                .directory(dir)
                .redirectErrorStream(true)
                .apply {
                    // Isolate child git from the caller's GIT_* variables and global/system config.
                    environment().keys.removeIf { it.startsWith("GIT_") }
                    environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                    // A fixture under @TempDir must never find an enclosing repository, wherever java.io.tmpdir lives.
                    environment()["GIT_CEILING_DIRECTORIES"] = temp.parentFile.absolutePath
                }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "${command.toList()} timed out" }
        return process.exitValue() to output
    }

    private fun git(vararg args: String) {
        val (exit, output) = exec(temp, "git", *args)
        check(exit == 0) { "git ${args.toList()} failed: $output" }
    }

    private fun commitAll() {
        git("add", ".")
        git("-c", "commit.gpgsign=false", "commit", "-q", "--no-verify", "-m", "fixture")
    }

    private fun repoWith(
        attributes: String,
        script: String,
        path: String = "tools/run.sh",
    ): File {
        git("init", "-q")
        git("config", "core.autocrlf", "false")
        git("config", "user.name", "ci")
        git("config", "user.email", "ci@example.invalid")
        File(temp, ".gitattributes").writeText(attributes)
        File(temp, "tools").mkdirs()
        File(temp, path).writeText(script)
        commitAll()
        return temp
    }

    private fun runGuard(repo: File): Pair<Int, String> = exec(File("."), "bash", script.path, repo.path)

    @Test
    fun `this repository's shell scripts keep LF`() {
        val (exit, output) = runGuard(File(".."))
        assertEquals(0, exit, output)
    }

    @Test
    fun `a CRLF script committed without the sh rule fails and is named`() {
        val repo = repoWith("*.jar binary\n", "#!/usr/bin/env bash\r\necho hi\r\n")
        val (exit, output) = runGuard(repo)
        assertEquals(1, exit, output)
        assertTrue(output.contains("::error::tools/run.sh"), output)
        assertTrue(output.contains("i/crlf"), output)
    }

    @Test
    fun `an sh rule resolving to crlf fails and is named`() {
        val repo = repoWith("*.sh text eol=crlf\n", "#!/usr/bin/env bash\necho hi\n")
        val (exit, output) = runGuard(repo)
        assertEquals(1, exit, output)
        assertTrue(output.contains("::error::tools/run.sh resolves to eol=crlf"), output)
    }

    @Test
    fun `an LF script without the sh rule fails on the attribute only`() {
        val repo = repoWith("*.jar binary\n", "#!/usr/bin/env bash\necho hi\n")
        val (exit, output) = runGuard(repo)
        assertEquals(1, exit, output)
        assertTrue(output.contains("::error::tools/run.sh resolves to eol=unspecified"), output)
        assertTrue(!output.contains("stored as"), output)
    }

    @Test
    fun `a CRLF script left unrenormalized under the LF rule fails on the index only`() {
        val repo = repoWith("*.jar binary\n", "#!/usr/bin/env bash\r\necho hi\r\n")
        File(repo, ".gitattributes").writeText("*.sh text eol=lf\n")
        git("add", ".gitattributes")
        git("-c", "commit.gpgsign=false", "commit", "-q", "--no-verify", "-m", "add rule")
        val (exit, output) = runGuard(repo)
        assertEquals(1, exit, output)
        assertTrue(output.contains("::error::tools/run.sh is stored as i/crlf"), output)
        assertTrue(!output.contains("resolves to"), output)
    }

    @Test
    fun `a directory that is not a git repository fails`() {
        val (exit, output) = runGuard(temp)
        assertEquals(2, exit, output)
        assertTrue(output.contains("::error::git ls-files failed"), output)
    }

    @Test
    fun `an sh rule that unsets text fails on the text attribute`() {
        val repo = repoWith("*.sh -text eol=lf\n", "#!/usr/bin/env bash\necho hi\n")
        val (exit, output) = runGuard(repo)
        assertEquals(1, exit, output)
        assertTrue(output.contains("::error::tools/run.sh has text attribute unset"), output)
    }

    @Test
    fun `an empty script under the LF rule passes`() {
        val repo = repoWith("*.sh text eol=lf\n", "")
        val (exit, output) = runGuard(repo)
        assertEquals(0, exit, output)
    }

    @Test
    fun `a script whose name has spaces and glob brackets is checked literally`() {
        val repo = repoWith("*.sh text eol=lf\n", "#!/usr/bin/env bash\necho hi\n", path = "tools/my [x] run.sh")
        val (exit, output) = runGuard(repo)
        assertEquals(0, exit, output)
    }
}
