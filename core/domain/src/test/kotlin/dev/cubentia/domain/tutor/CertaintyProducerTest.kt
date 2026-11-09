package dev.cubentia.domain.tutor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Story 4.1 AC 3 (AD-5): only `tutor/Assess.kt` produces a [Certainty] (and `tutor/Certainty.kt`
 * declares it). Every other main source in `:core:*` and `:app` may only match on one.
 */
class CertaintyProducerTest {
    @Test
    fun `no main source outside Assess produces a Certainty`() {
        // Tests run with the module dir as working dir.
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
        assertTrue(roots.any { it.path.replace('\\', '/').endsWith("core/domain/src/main") }, "found no main sources: $roots")
        val files =
            roots.flatMap { root ->
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
        assertTrue(files.any { it.name == "Assess.kt" }, "the scan did not reach tutor/Assess.kt")
        val offending =
            files
                .filterNot { file -> file.canonicalFile.relativeTo(repo.canonicalFile).invariantSeparatorsPath in ALLOWED }
                .flatMap { file -> producers(file.readText()).map { "${file.invariantSeparatorsPath}: $it" } }
        assertEquals(emptyList<String>(), offending, "a Certainty is produced only in tutor/Assess.kt")
    }

    @Test
    fun `the matcher flags production and allows a when branch`() {
        val producing =
            """
            import dev.cubentia.domain.tutor.Certainty
            val c = Certainty.VERIFIED
            """.trimIndent()
        assertEquals(listOf("VERIFIED"), producers(producing))
        val matching =
            """
            import dev.cubentia.domain.tutor.Certainty
            fun icon(c: Certainty) = when (c) {
                Certainty.VERIFIED -> "check"
                Certainty.LIKELY  -> "approx"
                Certainty.UNKNOWN->"?"
            }
            """.trimIndent()
        assertEquals(emptyList<String>(), producers(matching))
        val listedAndCompared =
            """
            import dev.cubentia.domain.tutor.Certainty
            /** Shown when VERIFIED. */
            fun sure(c: Certainty) = when (c) {
                Certainty.VERIFIED, Certainty.LIKELY -> c == Certainty.VERIFIED // UNKNOWN never
                Certainty.UNKNOWN -> c != Certainty.LIKELY
            }
            """.trimIndent()
        assertEquals(emptyList<String>(), producers(listedAndCompared))
        assertEquals(
            listOf("VERIFIED", "LIKELY"),
            producers("import dev.cubentia.domain.tutor.Certainty\nval cs = listOf(Certainty.VERIFIED, Certainty.LIKELY)"),
        )
        assertEquals(listOf("Certainty.valueOf"), producers("val c = Certainty.valueOf(name)"))
        assertEquals(listOf("Certainty.entries"), producers("val c = Certainty.entries.first()"))
        assertEquals(listOf("Certainty.values"), producers("val c = Certainty.values()[0]"))
        assertEquals(listOf("Certainty::valueOf"), producers("val f = Certainty::valueOf"))
        assertEquals(listOf("enumValues<Certainty>"), producers("val all = enumValues<Certainty>()"))
        assertEquals(listOf("enumValueOf<Certainty>"), producers("val c = enumValueOf<Certainty>(name)"))
        assertEquals(
            listOf("enumValueOf<dev.cubentia.domain.tutor.Certainty>"),
            producers("val c = enumValueOf<dev.cubentia.domain.tutor.Certainty>(name)"),
        )
        assertEquals(
            listOf("import dev.cubentia.domain.tutor.Certainty.LIKELY", "LIKELY"),
            producers("import dev.cubentia.domain.tutor.Certainty.LIKELY\nval c: Certainty = LIKELY"),
        )
        // A file that never names Certainty may use the words for its own constants.
        assertEquals(emptyList<String>(), producers("enum class Scan { UNKNOWN }"))
        assertEquals(emptyList<String>(), producers("val e = ErrorCategory.UNKNOWN_DISCREPANCY // Certainty"))
    }

    /** The Certainty-producing expressions in [source]: factory calls, then member imports, then bare constants. */
    private fun producers(source: String): List<String> {
        val calls = FACTORY.findAll(source).map { it.value }.toList()
        val imports = MEMBER_IMPORT.findAll(source).map { it.value.trim() }.toList()
        // A bare constant counts only where the file names Certainty, and never inside its own import line.
        val bare =
            if ("Certainty" in source) {
                BARE.findAll(MEMBER_IMPORT.replace(COMMENT.replace(source, ""), "")).map { it.value }.toList()
            } else {
                emptyList()
            }
        return calls + imports + bare
    }

    private companion object {
        val ALLOWED =
            setOf(
                "core/domain/src/main/kotlin/dev/cubentia/domain/tutor/Certainty.kt",
                "core/domain/src/main/kotlin/dev/cubentia/domain/tutor/Assess.kt",
            )
        val FACTORY =
            Regex(
                """\bCertainty\s*(\.|::)\s*(valueOf|values|entries)\b|\benum(Values|ValueOf)\s*<\s*([\w.]*\.)?Certainty\s*>""",
            )
        val MEMBER_IMPORT = Regex("""import\s+[\w.]*\bCertainty\.\w+""")
        val COMMENT = Regex("""/\*[\s\S]*?\*/|//[^\n]*""")

        // A constant matches when it is compared (`==`, `!=`) or opens a `when` branch, alone or
        // listed with others (`Certainty.VERIFIED, Certainty.LIKELY ->`); anything else produces one.
        val BARE =
            Regex(
                """(?<!(==|!=)\s{0,4}(Certainty\.)?)\b(VERIFIED|LIKELY|UNKNOWN)\b""" +
                    """(?!(\s*,\s*(Certainty\.)?(VERIFIED|LIKELY|UNKNOWN)\b)*\s*->)""",
            )
    }
}
