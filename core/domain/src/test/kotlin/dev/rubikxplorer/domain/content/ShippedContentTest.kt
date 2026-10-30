package dev.rubikxplorer.domain.content

import dev.rubikxplorer.domain.cube.PieceKind
import dev.rubikxplorer.domain.cube.ValidationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** AC 1: the shipped bundle loads clean, `en.json` has no duplicate keys, and code `Text(...)` calls match it. */
class ShippedContentTest {
    private val bundle: ContentBundle by lazy {
        val result = ContentLoader.load(ContentSource.SHIPPED)
        assertInstanceOf(ContentLoadResult.Loaded::class.java, result, result.toString())
        (result as ContentLoadResult.Loaded).bundle
    }

    @Test
    fun `the shipped bundle loads with schema version 1, a revision and no problems`() {
        assertEquals(ContentLoader.SUPPORTED_SCHEMA_VERSION, bundle.schemaVersion)
        assertTrue(Regex("[0-9a-f]{64}").matches(bundle.contentRevision), bundle.contentRevision)
        assertEquals(emptyList<ContentProblem>(), ContentValidator.validate(bundle))
    }

    @Test
    fun `the shipped en dot json has no duplicate keys`() {
        assertEquals(emptyList<String>(), ContentChecks.duplicateKeys(resourceText("content/strings/en.json")))
    }

    @Test
    fun `every Text call in core and app main sources names a key in en dot json with matching arguments`() {
        // Tests run with the module dir as working dir; scan every main source set in :core:* and :app.
        val repo = File("../..")
        val roots =
            (
                repo
                    .resolve("core")
                    .listFiles()
                    .orEmpty()
                    .map { it.resolve("src/main/kotlin") } + repo.resolve("app/src/main/kotlin")
            ).filter { it.isDirectory }
        assertTrue(roots.size >= 2, "found no source roots under ${repo.absolutePath}")
        val calls = roots.flatMap { root -> ContentChecks.textCallsUnder(root).map { it.copy(file = "${root.path}/${it.file}") } }
        assertTrue(calls.any { it.key == "msg.validation.parity" }, "the scan found no Text calls: $calls")
        assertEquals(emptyList<String>(), ContentChecks.textCallProblems(calls, bundle.strings))
    }

    @Test
    fun `every CubeValidator reason resolves, with calm copy`() {
        val reasons =
            listOf(
                ValidationResult.WrongColorCounts(emptyMap()),
                ValidationResult.ImpossiblePiece(emptyList(), PieceKind.EDGE),
                ValidationResult.TwistedCorner,
                ValidationResult.FlippedEdge,
                ValidationResult.ParityViolation,
            ).map { it.reason }
        reasons.forEach { reason ->
            val message = bundle.strings.getValue(reason.key)
            assertFalse('!' in message, message)
            assertEquals(IcuResult.Parsed(reason.args.keys), IcuArguments.parse(message), reason.key)
        }
    }

    @Test
    fun `a duplicate raw key in en dot json is reported`() {
        val raw = resourceText("content-fixtures/duplicate-string-key/strings/en.json")
        assertEquals(listOf("lesson.lesson-01-01.goal.1"), ContentChecks.duplicateKeys(raw))
        assertEquals(emptyList<String>(), ContentChecks.duplicateKeys(resourceText("content-fixtures/valid/strings/en.json")))
        assertEquals(emptyList<String>(), ContentChecks.duplicateKeys("""{"a": {"b": 1, "b": 2}, "c": "say \"a\": hi"}"""))
    }

    @Test
    fun `a Text call whose key is missing or whose arguments differ is reported with both argument sets`() {
        val calls = ContentChecks.textCalls("Snippet.kt", resourceText("content-fixtures/code-arg-mismatch/Snippet.kt.txt"))
        assertEquals(listOf(4, 5, 7), calls.map { it.line })
        val strings = (ContentLoader.load(fixture("valid")) as ContentLoadResult.Loaded).bundle.strings
        assertEquals(
            listOf(
                "Snippet.kt:5 msg.fixture.count passes [a] but its message takes [count, name]",
                "Snippet.kt:7 msg.fixture.absent is missing from en.json",
            ),
            ContentChecks.textCallProblems(calls, strings),
        )
    }

    @Test
    fun `Text calls with a computed key or argument map are skipped`() {
        val source =
            """
            /* Text("block.comment.key") */
            val a = Text(key)
            val b = Text("k.k.k", args)
            val c = Text("k.k.k", mapOf("x" to listOf(1, 2)), )
            val d = Text("bad.${'$'}key.k")
            """.trimIndent()
        assertEquals(listOf(ContentChecks.TextCall("S.kt", 4, "k.k.k", setOf("x"))), ContentChecks.textCalls("S.kt", source))
        assertEquals(
            listOf("S.kt:1 k.k.k has a malformed message"),
            ContentChecks.textCallProblems(listOf(ContentChecks.TextCall("S.kt", 1, "k.k.k", emptySet())), mapOf("k.k.k" to "{")),
        )
    }
}
