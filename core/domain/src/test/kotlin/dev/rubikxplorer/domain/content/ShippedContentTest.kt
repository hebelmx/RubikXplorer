package dev.rubikxplorer.domain.content

import dev.rubikxplorer.domain.cube.PieceKind
import dev.rubikxplorer.domain.cube.ValidationResult
import dev.rubikxplorer.domain.predicate.AllOf
import dev.rubikxplorer.domain.predicate.AlwaysTrue
import dev.rubikxplorer.domain.predicate.StateReached
import dev.rubikxplorer.domain.predicate.WhiteCornersSolved
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Story 2.1 AC 1 and Story 2.7 AC 1, 3: the shipped bundle loads clean with Stages 1–3 in stable IDs,
 * its copy follows the key rules and the voice guide, `en.json` has no duplicate keys, code
 * `Text(...)` calls match it, and tutor values live in `tutor-config.json`, not in code.
 */
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

    @Test
    fun `the shipped bundle holds Stages 1 to 3 with 3, 5 and 4 Lessons in stable IDs`() {
        assertEquals(
            listOf("stage-01-fundamentals", "stage-02-first-cross", "stage-03-first-layer-corners"),
            bundle.stages.map { it.id.value },
        )
        assertEquals(listOf(3, 5, 4), bundle.stages.map { it.lessons.size })
        bundle.stages.forEachIndexed { s, stage ->
            assertEquals(TextKey("stage.${stage.id}.title"), stage.title)
            stage.lessons.forEachIndexed { l, lesson ->
                assertEquals("lesson-%02d-%02d".format(s + 1, l + 1), lesson.id.value)
            }
        }
    }

    @Test
    fun `every shipped Lesson chains to the one before it, undoes to recover, and demonstrates its steps`() {
        val lessons = bundle.stages.flatMap { it.lessons }
        lessons.forEachIndexed { i, lesson ->
            assertEquals(listOfNotNull(lessons.getOrNull(i - 1)?.id), lesson.prerequisites, "${lesson.id}")
            assertEquals(RecoveryRef("recovery-undo-last-move"), lesson.recovery, "${lesson.id}")
            assertEquals(lesson.steps.joinToString(" ") { it.moves }, lesson.demonstration, "${lesson.id}")
        }
    }

    @Test
    fun `each Stage judges its Lessons by its own predicate and keeps what the Stage built`() {
        val cross = WhiteCrossSolved
        val layer = AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved))
        val (fundamentals, firstCross, corners) = bundle.stages
        fundamentals.lessons.forEach { lesson ->
            val exercise = StateReached(lesson.steps.joinToString(" ") { it.moves })
            assertEquals(exercise, lesson.completion, "${lesson.id}")
            assertEquals(AlwaysTrue, lesson.preserve, "${lesson.id}")
        }
        firstCross.lessons.forEach { assertEquals(cross to AlwaysTrue, it.completion to it.preserve, "${it.id}") }
        corners.lessons.forEach { assertEquals(layer to cross, it.completion to it.preserve, "${it.id}") }
    }

    @Test
    fun `every shipped text key has its slot and step number`() {
        bundle.stages.flatMap { it.lessons }.forEach { lesson ->
            val prefix = "lesson.${lesson.id}"
            assertEquals(TextKey("$prefix.objective"), lesson.objective)
            assertEquals(TextKey("$prefix.learner-action"), lesson.learnerAction)
            lesson.steps.forEachIndexed { i, step ->
                val n = i + 1
                assertEquals(
                    listOf("goal", "keep", "watch", "notice").map { TextKey("$prefix.$it.$n") },
                    listOf(step.goal, step.preserve, step.watch, step.notice),
                    "${lesson.id} step $n",
                )
            }
        }
    }

    @Test
    fun `every shipped content string is used and follows the voice guide`() {
        val titles = bundle.stages.map { it.title.value }.toSet()
        val used =
            titles +
                bundle.stages
                    .flatMap { it.lessons }
                    .flatMap { lesson ->
                        listOf(lesson.objective, lesson.learnerAction) +
                            lesson.steps.flatMap { listOf(it.goal, it.preserve, it.watch, it.notice) }
                    }.map { it.value }
        val content = bundle.strings.filterKeys { !it.startsWith("msg.") }
        assertEquals(content.keys, used, "every stage and lesson string is used by content, and only those")
        val problems =
            content.flatMap { (key, message) -> VoiceGuide.problems(message, title = key in titles).map { "$key $it: $message" } }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `the voice guide flags exclamations, judging words, first person, long sentences and bare Notation`() {
        assertEquals(emptyList<String>(), VoiceGuide.problems("You turn the right face, R."))
        assertEquals(emptyList<String>(), VoiceGuide.problems("First cross", title = true))
        val flagged =
            listOf(
                "You did it!",
                "That was wrong.",
                "Perform the turn.",
                "Let's turn R.",
                "Lets turn R.",
                "Let us turn R.",
                "Show me the edge.",
                "We turn the right face.",
                "This is the correct edge.",
                "One error here.",
                "R U R' U'.",
                "You turn the face",
                (1..VoiceGuide.MAX_SENTENCE_WORDS + 1).joinToString(" ") { "word" } + ".",
            )
        flagged.forEach { assertTrue(VoiceGuide.problems(it).isNotEmpty(), it) }
        assertTrue(VoiceGuide.problems("Great!", title = true).isNotEmpty())
    }

    @Test
    fun `the shipped tutor config loads, and tutor and session code declare no numeric constants`() {
        assertEquals(
            TutorConfig(
                maxDepth = 2,
                moveSet = "U U' U2 D D' D2 R R' R2 L L' L2 F F' F2 B B' B2",
                recoveryMaxDepth = 5,
                adaptive = AdaptiveThresholds(1, 2, 3, 2),
            ),
            bundle.tutorConfig,
        )
        val repo = File("../..")
        val roots =
            listOf(
                repo.resolve("core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor"),
                repo.resolve("core/application/src/main/kotlin/dev/rubikxplorer/application/session"),
            )
        roots.forEach { assertTrue(it.isDirectory, "missing ${it.absolutePath}") }
        val found =
            roots.flatMap { root ->
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file -> numericConstants(file.readText()).map { "${file.name}: $it" } }
                    .toList()
            }
        assertEquals(emptyList<String>(), found, "tutor values belong in tutor-config.json")
    }

    @Test
    fun `the numeric constant guard finds numbers, not other constants`() {
        val source =
            """
            const val MAX_DEPTH = 22
            private const val HINTS: Int = -1
            const val RATE = .5f
            const val NAME = "x"
            const val ON = true
            """.trimIndent()
        assertEquals(
            listOf("const val MAX_DEPTH = 22", "const val HINTS: Int = -1", "const val RATE = .5f"),
            numericConstants(source),
        )
    }

    private fun numericConstants(source: String): List<String> = NUMERIC_CONST.findAll(source).map { it.value.trim() }.toList()

    private companion object {
        val NUMERIC_CONST = Regex("const\\s+val\\s+\\w+\\s*(:\\s*\\w+\\s*)?=\\s*[-+]?\\.?\\d[\\w.]*")
    }
}
