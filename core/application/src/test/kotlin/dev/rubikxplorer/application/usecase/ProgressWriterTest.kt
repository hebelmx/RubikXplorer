package dev.rubikxplorer.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Story 7.1 AC 1 (AD-10, AR-14, AR-21): only `CompleteLesson`, `PassSkipCheck`, `PlaceFromScan`,
 * and `ResetProgress` write to `ProgressRepository`, and `ProgressDao` is strictly append-only
 * (`@Insert` and `SELECT` only, never update, upsert, or delete).
 */
class ProgressWriterTest {
    @Test
    fun `only the four Stage progress use cases call ProgressRepository append`() {
        val repo = File("../..")
        val modules =
            repo
                .resolve("core")
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory } + repo.resolve("app")
        val roots =
            modules
                .flatMap {
                    it
                        .resolve("src")
                        .listFiles()
                        .orEmpty()
                        .toList()
                }.filter { it.isDirectory && !it.name.contains("test", ignoreCase = true) }
        assertTrue(roots.any { it.path.replace('\\', '/').endsWith("core/application/src/main") })
        val files =
            roots.flatMap { root ->
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
        assertTrue(files.any { it.name == "CompleteLesson.kt" })
        val offending =
            files
                .filterNot { file ->
                    file.canonicalFile.relativeTo(repo.canonicalFile).invariantSeparatorsPath in ALLOWED_WRITERS
                }.filter { file ->
                    val text = COMMENT.replace(file.readText(), "")
                    ("ProgressRepository" in text || "ProgressDao" in text || "progress()" in text) &&
                        APPEND_OR_INSERT.containsMatchIn(text)
                }.map { it.canonicalFile.relativeTo(repo.canonicalFile).invariantSeparatorsPath }
        assertEquals(
            emptyList<String>(),
            offending,
            "only CompleteLesson, PassSkipCheck, PlaceFromScan, and ResetProgress may append progress events",
        )
    }

    @Test
    fun `ProgressDao has no update upsert or delete operations`() {
        val daoFile = File("../../app/src/main/kotlin/dev/rubikxplorer/app/data/ProgressDao.kt")
        assertTrue(daoFile.isFile, "ProgressDao.kt must exist")
        val text = COMMENT.replace(daoFile.readText(), "")
        assertFalse(MUTATION.containsMatchIn(text), "ProgressDao must be append-only with no update, upsert, or delete")
    }

    private companion object {
        val ALLOWED_WRITERS =
            setOf(
                "core/application/src/main/kotlin/dev/rubikxplorer/application/port/ProgressRepository.kt",
                "core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/CompleteLesson.kt",
                "core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/PassSkipCheck.kt",
                "core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/PlaceFromScan.kt",
                "core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/ResetProgress.kt",
                "app/src/main/kotlin/dev/rubikxplorer/app/data/ProgressDao.kt",
                "app/src/main/kotlin/dev/rubikxplorer/app/data/RoomProgressRepository.kt",
            )
        val COMMENT = Regex("""/\*[\s\S]*?\*/|//[^\n]*""")
        val APPEND_OR_INSERT = Regex("""\b(append|insert)\s*\(|::\s*(append|insert)\b""")
        val MUTATION =
            Regex(
                """@(Update|Delete|Upsert|RawQuery)\b|\b(UPDATE|DELETE|REPLACE|DROP|ALTER|TRUNCATE)\b""",
                RegexOption.IGNORE_CASE,
            )
    }
}
