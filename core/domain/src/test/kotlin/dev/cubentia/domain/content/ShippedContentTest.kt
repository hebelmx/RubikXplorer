package dev.cubentia.domain.content

import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.PieceKind
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.predicate.AllOf
import dev.cubentia.domain.predicate.AlwaysTrue
import dev.cubentia.domain.predicate.CubeSolved
import dev.cubentia.domain.predicate.LastLayerCornersPlaced
import dev.cubentia.domain.predicate.LastLayerCrossSolved
import dev.cubentia.domain.predicate.LastLayerOriented
import dev.cubentia.domain.predicate.MiddleLayerSolved
import dev.cubentia.domain.predicate.StateReached
import dev.cubentia.domain.predicate.WhiteCornersSolved
import dev.cubentia.domain.predicate.WhiteCrossSolved
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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
    fun `Stage 6 first-solve prompt ships a camera-free and a camera variant`() {
        // Story 8.7: CAMERA_SCAN off (v1.0) picks the camera-free copy; BETA/GA pick the camera copy.
        assertEquals(
            "Your cube should be solved now. Compare it with the picture, then tell me if it is.",
            bundle.strings["msg.physical.first-solve-prompt"],
        )
        assertEquals(
            "Your cube should be solved now. Check it with the camera, or tell me if it is.",
            bundle.strings["msg.physical.first-solve-prompt-camera"],
        )
        assertFalse("camera" in bundle.strings.getValue("msg.physical.first-solve-prompt").lowercase())
    }

    @Test
    fun `no Learner-facing string in en dot json or the app strings dot xml names Rubik`() {
        // Story 12.6 AC 4: "Rubik's" is a trademark; the app is Cubentia and says "the cube".
        val res = File("../../app/src/main/res")
        val xmlFiles =
            res
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && it.name.startsWith("values") }
                .map { it.resolve("strings.xml") }
                .filter { it.isFile }
        assertTrue(xmlFiles.isNotEmpty(), "found no strings.xml under ${res.absolutePath}")
        val texts = bundle.strings.mapKeys { (key, _) -> "en.json $key" } + xmlFiles.flatMap { androidStrings(it) }
        assertTrue(texts.keys.any { it.endsWith(" app_name") }, "the strings.xml scan found no app_name: ${texts.keys}")
        assertEquals(emptyList<String>(), trademarkProblems(texts))
    }

    @Test
    fun `physical-mode copy lives only in en dot json with its wording unchanged`() {
        val res = File("../../app/src/main/res")
        val names =
            res
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && it.name.startsWith("values") }
                .map { it.resolve("strings.xml") }
                .filter { it.isFile }
                .flatMap { androidStrings(it) }
                .map { (label, _) -> label.substringAfterLast(' ') }
        assertTrue("app_name" in names, "the strings.xml scan found no app_name under ${res.absolutePath}")
        assertEquals(
            emptyList<String>(),
            names.filter { it.startsWith("physical_") || it.startsWith("confirm_") || it in MIGRATED_RESOURCES },
        )
        assertEquals(TWO_LOOK_LABELS, bundle.strings.filterKeys { it in TWO_LOOK_LABELS })
        assertEquals(PHYSICAL_LABELS, bundle.strings.filterKeys { it in PHYSICAL_LABELS })
    }

    @Test
    fun `the trademark guard flags Rubik in any case`() {
        val samples =
            mapOf(
                "a" to "You solved the Rubik's cube!",
                "b" to "RUBIK",
                "c" to "You solved the cube!",
            )
        assertEquals(listOf("a", "b"), trademarkProblems(samples))
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
    fun `the shipped bundle holds Stages 1 to 7 with Lessons in Stages 1 to 6 and stable IDs`() {
        assertEquals(
            listOf(
                "stage-01-fundamentals",
                "stage-02-first-cross",
                "stage-03-first-layer-corners",
                "stage-04-middle-layer",
                "stage-05-last-layer-orientation",
                "stage-06-last-layer-completion",
                "stage-07-independent-practice",
            ),
            bundle.stages.map { it.id.value },
        )
        assertEquals(listOf(3, 5, 4, 4, 4, 3, 0), bundle.stages.map { it.lessons.size })
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
        val middle = AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved, MiddleLayerSolved))
        val oriented = AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved, MiddleLayerSolved, LastLayerOriented))
        val stages = bundle.stages
        val fundamentals = stages[0]
        val firstCross = stages[1]
        val corners = stages[2]
        val middleLayer = stages[3]
        val orientation = stages[4]
        val completion = stages[5]
        fundamentals.lessons.forEach { lesson ->
            val exercise = StateReached(lesson.steps.joinToString(" ") { it.moves })
            assertEquals(exercise, lesson.completion, "${lesson.id}")
            assertEquals(AlwaysTrue, lesson.preserve, "${lesson.id}")
        }
        firstCross.lessons.forEach { assertEquals(cross to AlwaysTrue, it.completion to it.preserve, "${it.id}") }
        corners.lessons.forEach { assertEquals(layer to cross, it.completion to it.preserve, "${it.id}") }
        middleLayer.lessons.forEach { assertEquals(middle to layer, it.completion to it.preserve, "${it.id}") }
        orientation.lessons.take(2).forEach { assertEquals(LastLayerCrossSolved to middle, it.completion to it.preserve, "${it.id}") }
        orientation.lessons.drop(2).forEach { assertEquals(LastLayerOriented to middle, it.completion to it.preserve, "${it.id}") }
        completion.lessons.take(1).forEach { assertEquals(LastLayerCornersPlaced to oriented, it.completion to it.preserve, "${it.id}") }
        completion.lessons.drop(1).forEach { assertEquals(CubeSolved to oriented, it.completion to it.preserve, "${it.id}") }
    }

    @Test
    fun `every shipped text key has its slot and step number`() {
        (bundle.stages + bundle.twoLookStages).flatMap { it.lessons }.forEach { lesson ->
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
        val titles =
            (
                (bundle.stages + bundle.twoLookStages).map { it.title } + bundle.cases.map { it.name } +
                    TextKey("stage.two-look-practice.title")
            ).map { it.value }.toSet()
        val used =
            titles +
                (bundle.stages + bundle.twoLookStages)
                    .flatMap { it.lessons }
                    .flatMap { lesson ->
                        listOf(lesson.objective, lesson.learnerAction) +
                            lesson.steps.flatMap { listOf(it.goal, it.preserve, it.watch, it.notice) }
                    }.map { it.value }
        val content = bundle.strings.filterKeys { !it.startsWith("msg.") && !it.startsWith("btn.") }
        assertEquals(content.keys, used, "every stage, lesson and case string is used by content, and only those")
        val problems =
            content.flatMap { (key, message) -> VoiceGuide.problems(message, title = key in titles).map { "$key $it: $message" } }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `the shipped bundle holds all 57 OLL and 21 PLL Cases with set, family and name`() {
        val oll = bundle.cases.filter { it.set == CaseSet.OLL }
        val pll = bundle.cases.filter { it.set == CaseSet.PLL }
        assertEquals((1..57).map { "case-oll-%02d".format(it) }, oll.map { it.id.value })
        assertEquals(PLL_LETTERS.map { "case-pll-$it" }, pll.map { it.id.value })
        assertEquals(
            78,
            bundle.cases
                .map { it.id }
                .toSet()
                .size,
        )
        bundle.cases.forEach { assertTrue(it.id.isWellFormed, "${it.id}") }
        bundle.cases.forEach { assertEquals(TextKey("case.${it.id}.name"), it.name, "${it.id}") }
        assertEquals((1..57).map { "OLL $it" }, oll.map { bundle.strings.getValue(it.name.value) })
        assertEquals(
            PLL_LETTERS.map { letters -> "${letters.replaceFirstChar { c -> c.uppercase() }} perm" },
            pll.map { bundle.strings.getValue(it.name.value) },
        )
        assertEquals(FAMILY_COUNTS, bundle.cases.groupingBy { it.family.value }.eachCount())
    }

    @Test
    fun `the shipped bundle holds 78 Algorithms, one primary per Case, each parsing to Moves`() {
        assertEquals(78, bundle.algorithms.size)
        assertEquals(
            78,
            bundle.algorithms
                .map { it.id }
                .toSet()
                .size,
        )
        assertEquals(
            78,
            bundle.algorithms
                .map { it.notation }
                .toSet()
                .size,
        )
        bundle.algorithms.forEach { algorithm ->
            assertTrue(algorithm.id.isWellFormed, "${algorithm.id}")
            val parsed = Notation.parse(algorithm.notation)
            assertInstanceOf(NotationResult.Parsed::class.java, parsed, "${algorithm.id}")
            assertTrue((parsed as NotationResult.Parsed).moves.isNotEmpty(), "${algorithm.id}")
            assertEquals(algorithm.notation, Notation.format(parsed.moves), "${algorithm.id}")
        }
        bundle.cases.forEach { case ->
            val primaries = bundle.algorithms.filter { it.caseId == case.id && it.isPrimary }
            assertEquals(listOf(AlgorithmId("alg-${case.id.value.removePrefix("case-")}-a")), primaries.map { it.id }, "${case.id}")
        }
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
                mastery = MasteryThresholds(2, 1),
            ),
            bundle.tutorConfig,
        )
        val repo = File("../..")
        val roots =
            listOf(
                repo.resolve("core/domain/src/main/kotlin/dev/cubentia/domain/tutor"),
                repo.resolve("core/application/src/main/kotlin/dev/cubentia/application/session"),
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

    private fun trademarkProblems(texts: Map<String, String>): List<String> =
        texts.filterValues { it.contains("rubik", ignoreCase = true) }.keys.toList()

    /** Every `<string>` and `<item>` (plurals, arrays) text in [file], keyed by file and resource name. */
    private fun androidStrings(file: File): List<Pair<String, String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val label = "${file.parentFile.name}/${file.name}"
        return listOf("string", "item").flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { i ->
                val element = nodes.item(i) as Element
                val name =
                    if (tag == "string" || element.getAttribute("name").isNotEmpty()) {
                        element.getAttribute("name")
                    } else {
                        val parent = element.parentNode as Element
                        val siblings = parent.getElementsByTagName("item")
                        val index = (0 until siblings.length).indexOfFirst { siblings.item(it) === element }
                        "${parent.getAttribute("name")}[${element.getAttribute("quantity").ifEmpty { index.toString() }}]"
                    }
                "$label $name" to element.textContent
            }
        }
    }

    private fun numericConstants(source: String): List<String> = NUMERIC_CONST.findAll(source).map { it.value.trim() }.toList()

    private companion object {
        val PLL_LETTERS =
            listOf("aa", "ab", "e", "f", "ga", "gb", "gc", "gd", "h", "ja", "jb", "na", "nb", "ra", "rb", "t", "ua", "ub", "v", "y", "z")

        /** Cases per family (Story 9.1 Design Notes). */
        val FAMILY_COUNTS =
            mapOf(
                "oll-dot" to 8,
                "oll-cross" to 7,
                "oll-t" to 2,
                "oll-square" to 2,
                "oll-c" to 2,
                "oll-w" to 2,
                "oll-p" to 4,
                "oll-line" to 4,
                "oll-fish" to 4,
                "oll-knight" to 4,
                "oll-awkward" to 4,
                "oll-l-shape" to 6,
                "oll-lightning" to 6,
                "oll-corners-oriented" to 2,
                "pll-edges-only" to 4,
                "pll-corners-only" to 3,
                "pll-adjacent-swap" to 10,
                "pll-diagonal-swap" to 4,
            )

        val MIGRATED_RESOURCES =
            setOf(
                "learn_faster_last_layer",
                "learn_two_look_locked_prerequisite",
                "learn_two_look_mastery",
                "learn_two_look_practice",
                "lesson_two_look_qualified",
                "lesson_two_look_not_qualified",
                "playback_follow_on_my_cube",
                "playback_cube_should_be_solved",
                "frame_expected",
            )

        /** Physical-mode copy that used to live in `strings.xml` too (Story 12.9): key to exact wording. */
        val TWO_LOOK_LABELS =
            mapOf(
                "msg.learn.faster-last-layer" to "Faster last layer",
                "msg.learn.two-look-locked-prerequisite" to "Complete or skip every Stage of the Course to unlock 2-look.",
                "msg.learn.two-look-mastery" to "Mastery: {done} of {total}",
                "btn.learn.two-look-practice" to "Practice the last layer",
                "msg.lesson.two-look.qualified" to "Last layer solved with 2-look, no hints.",
                "msg.lesson.two-look.not-qualified" to "Solved. Only 2-look algorithms with no hints count toward mastery.",
            )

        val PHYSICAL_LABELS =
            mapOf(
                "msg.physical.mode" to "Physical mode",
                "msg.physical.frame-expected" to "EXPECTED",
                "msg.physical.back-to-virtual" to "The on-screen cube now matches what we expected on yours.",
                "msg.physical.objective-met" to "If your cube matches the picture, this step is done.",
                "msg.playback.follow-on-my-cube" to "I'm following on my cube",
                "msg.playback.cube-should-be-solved" to "Your cube should be solved now.",
                "msg.physical.confirm.title" to "Confirm your cube",
                "btn.physical.confirm-matches" to "It matches",
                "btn.physical.confirm-enter" to "Enter it",
                "btn.physical.done" to "Done",
                "btn.physical.did-something-else" to "I did something else",
                "btn.physical.yes-its-solved" to "Yes, it's solved",
                "btn.physical.more-options" to "More options",
                "btn.physical.my-cube-doesnt-match" to "My cube doesn't match",
                "btn.physical.not-sure-what-i-did" to "I'm not sure what I did",
                "msg.physical.mismatch.which-turn" to "Which turn did you make?",
                "msg.physical.mismatch.one-face" to "Look at the front face — which corner is white?",
                "msg.physical.mismatch.re-enter" to "Let's re-enter your cube",
                "msg.physical.mismatch.candidate" to "Candidate: {candidate}",
                "msg.physical.mismatch.matches-after-all" to "It matches after all",
                "msg.physical.mismatch.next-question" to "Next question",
                "msg.physical.mismatch.corner-top-left" to "Top-left",
                "msg.physical.mismatch.corner-top-right" to "Top-right",
                "msg.physical.mismatch.corner-bottom-left" to "Bottom-left",
                "msg.physical.mismatch.corner-bottom-right" to "Bottom-right",
                "msg.physical.mismatch.corner-none" to "None of these",
            )

        val NUMERIC_CONST = Regex("const\\s+val\\s+\\w+\\s*(:\\s*\\w+\\s*)?=\\s*[-+]?\\.?\\d[\\w.]*")
    }
}
