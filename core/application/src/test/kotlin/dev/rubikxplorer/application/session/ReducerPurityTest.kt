package dev.rubikxplorer.application.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** AC 3: the session sources never reach for the wall clock, randomness or the system. */
class ReducerPurityTest {
    private val dir = File("src/main/kotlin/dev/rubikxplorer/application/session")

    private val forbidden =
        listOf(
            Regex("\\bClock\\b"),
            Regex("\\bRandomSource\\b"),
            Regex("\\bnow\\s*\\("),
            Regex("\\bSystem\\."),
            Regex("kotlin\\.random"),
            Regex("java\\.util\\.Random"),
            Regex("\\bRandom\\b"),
            Regex("java\\.time\\.Clock"),
        )

    /** Code without comments and string literals, so prose about the rule does not trip it. */
    private fun code(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")
            .replace(Regex("\"(\\\\.|[^\"\\\\])*\""), "\"\"")

    @Test
    fun `no session source reads a clock, randomness or the system`() {
        val files = dir.listFiles { f -> f.extension == "kt" }.orEmpty().sortedBy { it.name }
        assertTrue(files.any { it.name == "Reduce.kt" }, "scan found no session sources in ${dir.absolutePath}")
        val hits =
            files.flatMap { file ->
                val text = code(file.readText())
                forbidden.filter { it.containsMatchIn(text) }.map { "${file.name}: ${it.pattern}" }
            }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun `the scan catches a clock read`() {
        val sample = "fun reduce() { val t = clock.now() } // Clock is fine in a comment"
        assertTrue(forbidden.any { it.containsMatchIn(code(sample)) })
        assertTrue(forbidden.none { it.containsMatchIn(code("// Clock, RandomSource, System.x")) })
    }
}
