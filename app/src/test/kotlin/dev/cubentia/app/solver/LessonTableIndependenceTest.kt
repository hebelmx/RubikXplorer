package dev.cubentia.app.solver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Story 3.8 I/O matrix "Lesson while tables load": Lessons never call or await table readiness, so
 * no Lesson source may reference the solver or its table delivery.
 */
class LessonTableIndependenceTest {
    @Test
    fun `lesson sources never reference the solver or pruning-table delivery`() {
        val lessonDir = File("src/main/kotlin/dev/cubentia/app/ui/lesson")
        val sources = lessonDir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "no Lesson sources found under ${lessonDir.absolutePath}")
        val forbidden = listOf("dev.cubentia.solver", "dev.cubentia.app.solver", "SolverSource", "PruningTable")
        val offenders = sources.filter { file -> forbidden.any { it in file.readText() } }.map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }
}
