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
    /** Every source dir testDebugUnitTest compiles; a missing one is skipped. */
    private val sourceDirs = listOf("src/test/kotlin", "src/testDebug/kotlin", "src/sharedTest/kotlin").map(::File)

    private val sources =
        sourceDirs.filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }

    @Test
    fun noWaitUntilReliesOnTheDefaultTimeout() {
        assertTrue("no test sources found under ${File("src/test/kotlin").absolutePath}", sources.isNotEmpty())
        val defaultWait = Regex("""\bwaitUntil\s*\{""")
        // These forms take the timeout as a named argument; without timeoutMillis on the call they use 1 s.
        val namedTimeoutWait =
            Regex("""\bwaitUntil(?:\(\s*conditionDescription|(?:AtLeastOneExists|ExactlyOneExists|DoesNotExist|NodeCount)\s*\()""")
        val offenders =
            sources.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val default =
                        defaultWait.containsMatchIn(line) ||
                            (namedTimeoutWait.containsMatchIn(line) && !line.contains("timeoutMillis"))
                    if (default) "${file.path}:${index + 1}: ${line.trim()}" else null
                }
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
