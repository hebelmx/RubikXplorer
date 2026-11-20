package dev.cubentia.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Epic 6 retro item 2 (Compose clock stabilization): under CI load the 1 s default `waitUntil` timeout
 * flakes, so every wait in the unit-test sources passes an explicit timeout, and `LessonScreenTest`
 * restores the paused main clock in its `@After`.
 */
class ComposeTestTimingGuardTest {
    /** Every source dir testDebugUnitTest compiles (spike3dTest only with -Pcubentia.spike3d=true); a missing one is skipped. */
    private val sourceDirs =
        listOf("src/test/kotlin", "src/testDebug/kotlin", "src/sharedTest/kotlin", "src/spike3dTest/kotlin").map(::File)

    private val sources =
        sourceDirs.filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }

    /** Text from the `(` at [open] to its matching `)`, across lines; parens inside strings are not special-cased. */
    private fun argsAt(
        text: String,
        open: Int,
    ): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(open + 1, i)
            }
        }
        return text.substring(open + 1)
    }

    /** Top-level arguments of a call's argument text (commas inside (), {} or [] do not split). */
    private fun topLevelArgs(args: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        args.forEachIndexed { i, c ->
            when (c) {
                '(', '{', '[' -> {
                    depth++
                }

                ')', '}', ']' -> {
                    depth--
                }

                ',' -> {
                    if (depth == 0) {
                        parts += args.substring(start, i)
                        start = i + 1
                    }
                }
            }
        }
        parts += args.substring(start)
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Positional arguments before timeoutMillis, per ComposeUiTest: waitUntil(timeoutMillis, ...), *Exists(matcher, timeoutMillis). */
    private val argsBeforeTimeout =
        mapOf("" to 0, "AtLeastOneExists" to 1, "ExactlyOneExists" to 1, "DoesNotExist" to 1, "NodeCount" to 2)

    /** True when the call starting at [match] passes no timeout, so it uses the 1 s default. */
    private fun usesDefaultTimeout(
        text: String,
        match: MatchResult,
    ): Boolean {
        val variant = match.groupValues[1]
        val before = argsBeforeTimeout[variant] ?: return false
        if (match.groupValues[2] == "{") return true // trailing lambda only, no parens
        val args = topLevelArgs(argsAt(text, match.range.last))
        if (args.any { Regex("""^timeoutMillis\s*=""").containsMatchIn(it) }) return false
        val positional = args.filterNot { Regex("""^\w+\s*=""").containsMatchIn(it) }
        // A trailing lambda is outside the parens, so every positional argument here counts.
        return positional.size <= before
    }

    @Test
    fun noWaitUntilReliesOnTheDefaultTimeout() {
        assertTrue("no test sources found under ${sourceDirs.map { it.absolutePath }}", sources.isNotEmpty())
        // Whole-file scan, so calls split across lines are seen as one call.
        val call = Regex("""\bwaitUntil(\w*)\s*([({])""")
        val offenders =
            sources.flatMap { file ->
                val text = file.readText().replace("\r\n", "\n")
                call
                    .findAll(text)
                    .filter { usesDefaultTimeout(text, it) }
                    .map { match ->
                        val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                        "${file.path}:$line: ${text.lineSequence().drop(line - 1).first().trim()}"
                    }.toList()
            }
        assertTrue("waitUntil without a timeout (pass 5_000):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun lessonScreenTestRestoresTheMainClockInTearDown() {
        val text = sources.single { it.name == "LessonScreenTest.kt" }.readText().replace("\r\n", "\n")
        assertTrue("LessonScreenTest no longer pauses the clock", text.contains("mainClock.autoAdvance = false"))
        val tearDown =
            Regex("""(?s)@After\s+fun\s+\w+\(\)\s*\{(.*?)\n    }""").find(text)?.groupValues?.get(1)
                ?: error("LessonScreenTest has no @After")
        assertTrue("@After must reset autoAdvance = true:\n$tearDown", tearDown.contains("autoAdvance = true"))
    }
}
