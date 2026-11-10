package dev.cubentia.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AlgorithmStatusWriterTest {
    @Test
    fun `only SetAlgorithmStatus writes Algorithm Status events`() {
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
        assertTrue(files.any { it.name == "SetAlgorithmStatus.kt" })
        val offending =
            files
                .filterNot { file ->
                    file.canonicalFile.relativeTo(repo.canonicalFile).invariantSeparatorsPath in ALLOWED_WRITERS
                }.filter { file ->
                    val text = COMMENT.replace(file.readText(), "")
                    ("AlgorithmStatusRepository" in text || "AlgorithmStatusDao" in text || "algorithmStatuses()" in text) &&
                        APPEND_OR_INSERT.containsMatchIn(text)
                }.map { it.canonicalFile.relativeTo(repo.canonicalFile).invariantSeparatorsPath }
        assertEquals(
            emptyList<String>(),
            offending,
            "only SetAlgorithmStatus may append status events",
        )
    }

    @Test
    fun `AlgorithmStatusDao has no update upsert or delete operations`() {
        val daoFile = File("../../app/src/main/kotlin/dev/cubentia/app/data/AlgorithmStatusDao.kt")
        assertTrue(daoFile.isFile, "AlgorithmStatusDao.kt must exist")
        val text = COMMENT.replace(daoFile.readText(), "")
        assertFalse(MUTATION.containsMatchIn(text), "AlgorithmStatusDao must be append-only with no update, upsert, or delete")
    }

    private companion object {
        val ALLOWED_WRITERS =
            setOf(
                "core/application/src/main/kotlin/dev/cubentia/application/port/AlgorithmStatusRepository.kt",
                "core/application/src/main/kotlin/dev/cubentia/application/usecase/SetAlgorithmStatus.kt",
                "app/src/main/kotlin/dev/cubentia/app/data/AlgorithmStatusDao.kt",
                "app/src/main/kotlin/dev/cubentia/app/data/RoomAlgorithmStatusRepository.kt",
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
