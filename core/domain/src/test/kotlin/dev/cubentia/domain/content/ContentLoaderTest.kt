package dev.cubentia.domain.content

import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.NotationError
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.predicate.AllOf
import dev.cubentia.domain.predicate.AnyOf
import dev.cubentia.domain.predicate.CubeSolved
import dev.cubentia.domain.predicate.LastLayerCornersPlaced
import dev.cubentia.domain.predicate.LastLayerCrossSolved
import dev.cubentia.domain.predicate.LastLayerOriented
import dev.cubentia.domain.predicate.MiddleLayerSolved
import dev.cubentia.domain.predicate.StateReached
import dev.cubentia.domain.predicate.WhiteCornersSolved
import dev.cubentia.domain.predicate.WhiteCrossSolved
import dev.cubentia.domain.predicate.WhitePieceSolved
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.cubentia.domain.cube.Notation as CubeNotation

/** AC 1–2: one test per I/O row; every failure is a sealed [ContentLoadResult.Failed], never a throw. */
class ContentLoaderTest {
    private fun loaded(source: ContentSource): ContentBundle {
        val result = ContentLoader.load(source)
        assertInstanceOf(ContentLoadResult.Loaded::class.java, result, result.toString())
        return (result as ContentLoadResult.Loaded).bundle
    }

    private fun failed(source: ContentSource): ContentLoadError {
        val result = ContentLoader.load(source)
        assertInstanceOf(ContentLoadResult.Failed::class.java, result, result.toString())
        return (result as ContentLoadResult.Failed).error
    }

    private fun problems(variant: String): List<ContentProblem> {
        val error = failed(fixture(variant))
        assertInstanceOf(ContentLoadError.Invalid::class.java, error, error.toString())
        return (error as ContentLoadError.Invalid).problems
    }

    private fun malformed(variant: String): ContentLoadError.Malformed {
        val error = failed(fixture(variant))
        assertInstanceOf(ContentLoadError.Malformed::class.java, error, error.toString())
        return error as ContentLoadError.Malformed
    }

    @Test
    fun `the valid fixture loads with its Lesson decoded field by field`() {
        val bundle = loaded(fixture("valid"))
        assertEquals(1, bundle.schemaVersion)
        assertTrue(REVISION.matches(bundle.contentRevision), bundle.contentRevision)
        val lesson =
            bundle.stages
                .single()
                .lessons
                .single()
        assertEquals(StageId("stage-01-basics"), lesson.stageId)
        assertEquals(WhiteCrossSolved, lesson.completion)
        assertEquals(WhiteCrossSolved, lesson.preserve)
        assertEquals(RecoveryRef("recovery-undo-last-move"), lesson.recovery)
        assertEquals("F'", lesson.steps.single().moves)
        assertEquals(emptyList<ContentProblem>(), ContentValidator.validate(bundle))
    }

    @Test
    fun `the valid fixture's Demonstration takes its entry state to the Completion Predicate`() {
        val lesson =
            loaded(fixture("valid"))
                .stages
                .single()
                .lessons
                .single()
        val entry = (FaceletString.decode(lesson.entryState) as FaceletStringResult.Decoded).facelets
        val state = (CubeValidator.validate(entry) as ValidationResult.Valid).state
        val moves = (CubeNotation.parse(lesson.demonstration) as NotationResult.Parsed).moves
        assertFalse(lesson.completion.isSatisfied(state))
        assertTrue(lesson.completion.isSatisfied(state.apply(moves)))
    }

    @Test
    fun `the same bytes give the same revision and one changed byte a different one`() {
        val first = loaded(fixture("valid")).contentRevision
        assertEquals(first, loaded(fixture("valid")).contentRevision)
        val valid = fixture("valid")
        val files = listOf(ContentLoader.STRINGS, "stages/stage-01-basics.json", ContentLoader.MANIFEST, ContentLoader.TUTOR_CONFIG)
        val revisions =
            files.map { changed ->
                val touched =
                    ContentSource { path ->
                        valid.read(path)?.let { if (path == changed) it + ' '.code.toByte() else it }
                    }
                loaded(touched).contentRevision.also { assertNotEquals(first, it, changed) }
            }
        assertEquals(4, revisions.toSet().size)
    }

    @Test
    fun `a missing manifest or stage file is MissingResource`() {
        assertEquals(ContentLoadError.MissingResource("bundle.json"), failed(ContentSource.classpath("content-fixtures/absent")))
        assertEquals(ContentLoadError.MissingResource("stages/stage-09-missing.json"), failed(fixture("missing-stage-file")))
    }

    @Test
    fun `missing strings are MissingResource`() {
        val valid = fixture("valid")
        val noStrings = ContentSource { path -> if (path == ContentLoader.STRINGS) null else valid.read(path) }
        assertEquals(ContentLoadError.MissingResource(ContentLoader.STRINGS), failed(noStrings))
    }

    @Test
    fun `bad JSON, a missing Lesson field, an unknown key and an unknown predicate are Malformed`() {
        listOf("bad-json", "missing-recovery", "unknown-key", "unknown-predicate").forEach { variant ->
            assertEquals("stages/stage-01-basics.json", malformed(variant).path, variant)
        }
        assertTrue("recovery" in malformed("missing-recovery").detail)
        assertTrue("hint" in malformed("unknown-key").detail)
        assertTrue("nope" in malformed("unknown-predicate").detail)
    }

    @Test
    fun `a Lesson using every predicate variant loads with no problems`() {
        val bundle = loaded(fixture("all-predicates"))
        val lesson =
            bundle.stages
                .single()
                .lessons
                .single()
        assertEquals(AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved)), lesson.completion)
        assertEquals(
            AnyOf(
                listOf(
                    StateReached("F"),
                    WhiteCrossSolved,
                    WhitePieceSolved("white-red-blue"),
                    MiddleLayerSolved,
                    LastLayerCrossSolved,
                    LastLayerOriented,
                    LastLayerCornersPlaced,
                    CubeSolved,
                ),
            ),
            lesson.preserve,
        )
        assertEquals(emptyList<ContentProblem>(), ContentValidator.validate(bundle))
        val entry = (FaceletString.decode(lesson.entryState) as FaceletStringResult.Decoded).facelets
        val state = (CubeValidator.validate(entry) as ValidationResult.Valid).state
        assertTrue(lesson.preserve.isSatisfied(state))
        assertFalse(lesson.completion.isSatisfied(state))
        assertTrue(lesson.completion.isSatisfied(state.apply((CubeNotation.parse(lesson.demonstration) as NotationResult.Parsed).moves)))
    }

    @Test
    fun `an unknown nested predicate, bad predicate moves and an empty allOf are Malformed`() {
        listOf("unknown-nested-predicate", "bad-predicate-moves", "empty-all-of").forEach { variant ->
            assertEquals("stages/stage-01-basics.json", malformed(variant).path, variant)
        }
        assertTrue("nope" in malformed("unknown-nested-predicate").detail)
        assertTrue("not valid Notation: \"Q\"" in malformed("bad-predicate-moves").detail)
        assertTrue("allOf needs at least one predicate" in malformed("empty-all-of").detail)
    }

    @Test
    fun `a manifest without an integer schemaVersion, or not an object, is Malformed`() {
        listOf("""{"stages": []}""", """{"schemaVersion": "1", "stages": []}""", "[]", "{").forEach { manifest ->
            val source = ContentSource { path -> if (path == ContentLoader.MANIFEST) manifest.toByteArray() else null }
            val error = failed(source)
            assertInstanceOf(ContentLoadError.Malformed::class.java, error, manifest)
            assertEquals(ContentLoader.MANIFEST, (error as ContentLoadError.Malformed).path)
        }
    }

    @Test
    fun `a source that throws is Malformed, not a thrown exception`() {
        val error = failed { throw IllegalStateException("disk") }
        assertEquals(ContentLoader.MANIFEST, (error as ContentLoadError.Malformed).path)
        assertTrue("disk" in error.detail)
    }

    @Test
    fun `a manifest for another schema version is UnsupportedSchemaVersion`() {
        assertEquals(ContentLoadError.UnsupportedSchemaVersion(2, 1), failed(fixture("schema-version-2")))
    }

    @Test
    fun `a text key missing from en dot json is Invalid`() {
        assertEquals(listOf(ContentProblem.MissingTextKey(TextKey("lesson.lesson-01-01.watch.1"))), problems("missing-text-key"))
    }

    @Test
    fun `a content text key whose message takes arguments is Invalid`() {
        assertEquals(
            listOf(ContentProblem.TextKeyTakesArguments(TextKey("lesson.lesson-01-01.objective"), setOf("count"))),
            problems("text-key-with-args"),
        )
    }

    @Test
    fun `a text key owned by another Lesson is Invalid`() {
        assertEquals(
            listOf(ContentProblem.MalformedTextKey("lesson-01-01", TextKey("lesson.lesson-01-02.objective"))),
            problems("bad-key-format"),
        )
    }

    @Test
    fun `a duplicate Lesson id is Invalid`() {
        assertEquals(listOf(ContentProblem.DuplicateLessonId(LessonId("lesson-01-01"))), problems("duplicate-lesson-id"))
    }

    @Test
    fun `bad Notation is Invalid`() {
        assertEquals(
            listOf(
                ContentProblem.BadNotation(LessonId("lesson-01-01"), ContentValidator.DEMONSTRATION_FIELD, NotationError.BadToken("Q", 1)),
            ),
            problems("bad-notation"),
        )
    }

    @Test
    fun `an impossible entry state is Invalid`() {
        assertEquals(
            listOf(ContentProblem.ImpossibleEntryState(LessonId("lesson-01-01"), ValidationResult.TwistedCorner)),
            problems("invalid-entry-state"),
        )
    }

    /** The valid fixture with its one Lesson's JSON members overridden by those [change] returns for it. */
    private fun lessonVariant(change: (JsonObject) -> Map<String, JsonElement>): ContentSource {
        val valid = fixture("valid")
        val path = "stages/stage-01-basics.json"
        val stage = ContentLoader.json.parseToJsonElement(resourceText("content-fixtures/valid/$path")).jsonObject
        val lesson =
            stage
                .getValue("lessons")
                .jsonArray
                .single()
                .jsonObject
        val changed = JsonObject(stage + ("lessons" to JsonArray(listOf(JsonObject(lesson + change(lesson))))))
        return ContentSource { if (it == path) changed.toString().toByteArray() else valid.read(it) }
    }

    private fun stepMoves(
        lesson: JsonObject,
        vararg moves: String,
    ): JsonArray {
        val step =
            lesson
                .getValue("steps")
                .jsonArray
                .single()
                .jsonObject
        return JsonArray(moves.map { JsonObject(step + ("moves" to JsonPrimitive(it))) })
    }

    private fun invalid(source: ContentSource): List<ContentProblem> {
        val error = failed(source)
        assertInstanceOf(ContentLoadError.Invalid::class.java, error, error.toString())
        return (error as ContentLoadError.Invalid).problems
    }

    @Test
    fun `a Lesson with no steps, an empty step, a no-op step or a mismatched Demonstration is Invalid`() {
        val id = LessonId("lesson-01-01")
        assertEquals(listOf(ContentProblem.NoSteps(id)), invalid(lessonVariant { mapOf("steps" to JsonArray(emptyList())) }))
        assertEquals(
            listOf(ContentProblem.EmptyStep(id, 0)),
            invalid(lessonVariant { mapOf("demonstration" to JsonPrimitive(""), "steps" to stepMoves(it, "")) }),
        )
        assertEquals(
            listOf(ContentProblem.NoOpStep(id, 0)),
            invalid(lessonVariant { mapOf("demonstration" to JsonPrimitive("R R' F'"), "steps" to stepMoves(it, "R R'", "F'")) }),
        )
        assertEquals(
            listOf(ContentProblem.DemonstrationMismatch(id)),
            invalid(lessonVariant { mapOf("demonstration" to JsonPrimitive("F")) }),
        )
    }

    @Test
    fun `a Demonstration in equivalent Notation loads`() {
        loaded(lessonVariant { mapOf("demonstration" to JsonPrimitive("F' R2'"), "steps" to stepMoves(it, "F'", "R2")) })
    }

    @Test
    fun `the tutor config loads strictly, and a missing one is MissingResource`() {
        assertEquals(SHIPPED_CONFIG, loaded(fixture("valid")).tutorConfig)
        assertEquals(SHIPPED_CONFIG, loaded(ContentSource.SHIPPED).tutorConfig)
        assertEquals(18, SHIPPED_CONFIG.moves.size)
        val valid = fixture("valid")
        listOf(
            """{"x":1}""",
            "{}",
            """{"maxDepth":2}""",
            """{"maxDepth":2,"moveSet":"U"}""",
            """{"maxDepth":2,"moveSet":"U","recoveryMaxDepth":5}""",
            "[]",
            "{",
        ).forEach { config ->
            val source = ContentSource { path -> if (path == ContentLoader.TUTOR_CONFIG) config.toByteArray() else valid.read(path) }
            val error = failed(source)
            assertInstanceOf(ContentLoadError.Malformed::class.java, error, config)
            assertEquals(ContentLoader.TUTOR_CONFIG, (error as ContentLoadError.Malformed).path)
        }
        val noConfig = ContentSource { path -> if (path == ContentLoader.TUTOR_CONFIG) null else valid.read(path) }
        assertEquals(ContentLoadError.MissingResource(ContentLoader.TUTOR_CONFIG), failed(noConfig))
    }

    @Test
    fun `a tutor config with maxDepth outside 1 to 3, recoveryMaxDepth outside 1 to 8 or an invalid or empty moveSet does not load`() {
        val valid = fixture("valid")

        fun withConfig(config: String) =
            ContentSource { path -> if (path == ContentLoader.TUTOR_CONFIG) config.toByteArray() else valid.read(path) }

        val depth = ContentValidator.MAX_DEPTH_FIELD
        val moveSet = ContentValidator.MOVE_SET_FIELD
        val recovery = ContentValidator.RECOVERY_MAX_DEPTH_FIELD
        val mastery = ContentValidator.MASTERY_FIELD
        val cases =
            mapOf(
                """{"maxDepth":0,"moveSet":"U","recoveryMaxDepth":5,$ADAPTIVE_JSON,$MASTERY_JSON}""" to depth,
                """{"maxDepth":4,"moveSet":"U","recoveryMaxDepth":5,$ADAPTIVE_JSON,$MASTERY_JSON}""" to depth,
                """{"maxDepth":2,"moveSet":"U Q","recoveryMaxDepth":5,$ADAPTIVE_JSON,$MASTERY_JSON}""" to moveSet,
                """{"maxDepth":2,"moveSet":"  ","recoveryMaxDepth":5,$ADAPTIVE_JSON,$MASTERY_JSON}""" to moveSet,
                """{"maxDepth":2,"moveSet":"U","recoveryMaxDepth":0,$ADAPTIVE_JSON,$MASTERY_JSON}""" to recovery,
                """{"maxDepth":2,"moveSet":"U","recoveryMaxDepth":9,$ADAPTIVE_JSON,$MASTERY_JSON}""" to recovery,
                """{"maxDepth":2,"moveSet":"U","recoveryMaxDepth":5,${ADAPTIVE_JSON.replace(
                    "\"guideAfterMistakes\":3",
                    "\"guideAfterMistakes\":11",
                )},$MASTERY_JSON}""" to
                    "adaptive.guideAfterMistakes",
                """{"maxDepth":2,"moveSet":"U","recoveryMaxDepth":5,${ADAPTIVE_JSON.replace(
                    "\"demonstrateAfterMistakes\":2",
                    "\"demonstrateAfterMistakes\":1",
                )},$MASTERY_JSON}""" to
                    ContentValidator.ADAPTIVE_FIELD,
                """{"maxDepth":2,"moveSet":"U","recoveryMaxDepth":5,$ADAPTIVE_JSON,""" +
                    """"mastery":{"solvesRequired":0,"reducedHelpRequired":0}}""" to
                    "mastery.solvesRequired",
                """{"maxDepth":2,"moveSet":"U","recoveryMaxDepth":5,$ADAPTIVE_JSON,""" +
                    """"mastery":{"solvesRequired":2,"reducedHelpRequired":3}}""" to
                    mastery,
            )
        cases.forEach { (config, field) ->
            val problems = invalid(withConfig(config))
            assertEquals(1, problems.size, "$config: $problems")
            val problem = problems.single()
            assertInstanceOf(ContentProblem.InvalidTutorConfig::class.java, problem, config)
            assertEquals(field, (problem as ContentProblem.InvalidTutorConfig).field, config)
        }
        listOf(
            1,
            3,
        ).forEach { loaded(withConfig("""{"maxDepth":$it,"moveSet":"U R","recoveryMaxDepth":5,$ADAPTIVE_JSON,$MASTERY_JSON}""")) }
    }

    /** The valid fixture with a manifest listing [files] (`path to JSON`), and strings naming `case-oll-45` and `case-oll-46`. */
    private fun withAlgorithmFiles(files: Map<String, String>): ContentSource {
        val valid = fixture("valid")
        val listed = files.keys.joinToString(",") { "\"$it\"" }
        val manifest = """{"schemaVersion":1,"stages":["stages/stage-01-basics.json"],"algorithms":[$listed]}"""
        val strings = ContentLoader.json.parseToJsonElement(resourceText("content-fixtures/valid/strings/en.json")).jsonObject
        val names = listOf("45", "46").associate { "case.case-oll-$it.name" to JsonPrimitive("OLL $it") }
        val named = JsonObject(strings + names).toString()
        return ContentSource { path ->
            when (path) {
                ContentLoader.MANIFEST -> manifest.toByteArray()
                ContentLoader.STRINGS -> named.toByteArray()
                else -> files[path]?.toByteArray() ?: valid.read(path)
            }
        }
    }

    @Test
    fun `Algorithm files load in manifest order`() {
        val other = ALGORITHM_FILE.replace("45", "46")
        val bundle = loaded(withAlgorithmFiles(mapOf("algorithms/a.json" to ALGORITHM_FILE, "algorithms/b.json" to other)))
        assertEquals(listOf(CaseId("case-oll-45"), CaseId("case-oll-46")), bundle.cases.map { it.id })
        assertEquals(listOf(AlgorithmId("alg-oll-45-a"), AlgorithmId("alg-oll-46-a")), bundle.algorithms.map { it.id })
        assertEquals(CaseSet.OLL, bundle.cases.first().set)
        assertEquals(CaseId("case-oll-45"), bundle.algorithms.first().caseId)
        assertTrue(bundle.algorithms.first().isPrimary)
    }

    @Test
    fun `an Algorithm file's bytes change the revision`() {
        val empty = """{"cases":[],"algorithms":[]}"""
        val first = loaded(withAlgorithmFiles(mapOf("algorithms/a.json" to empty))).contentRevision
        assertEquals(first, loaded(withAlgorithmFiles(mapOf("algorithms/a.json" to empty))).contentRevision)
        assertNotEquals(first, loaded(withAlgorithmFiles(mapOf("algorithms/a.json" to "$empty "))).contentRevision)
    }

    @Test
    fun `the same Case and Algorithm in two files are duplicates`() {
        assertEquals(
            listOf(
                ContentProblem.DuplicateCaseId(CaseId("case-oll-45")),
                ContentProblem.DuplicateAlgorithmId(AlgorithmId("alg-oll-45-a")),
            ),
            invalid(withAlgorithmFiles(mapOf("algorithms/a.json" to ALGORITHM_FILE, "algorithms/b.json" to ALGORITHM_FILE))),
        )
    }

    @Test
    fun `a missing Algorithm file is MissingResource`() {
        val valid = fixture("valid")
        val manifest = """{"schemaVersion":1,"stages":[],"algorithms":["algorithms/absent.json"]}"""
        val source = ContentSource { path -> if (path == ContentLoader.MANIFEST) manifest.toByteArray() else valid.read(path) }
        assertEquals(ContentLoadError.MissingResource("algorithms/absent.json"), failed(source))
    }

    @Test
    fun `an unknown key or set value in an Algorithm file is Malformed, as is a manifest without algorithms`() {
        val path = "algorithms/a.json"
        val set = "\"set\":\"OLL\""
        listOf(
            ALGORITHM_FILE.replace(set, "\"set\":\"F2L\"") to "F2L",
            ALGORITHM_FILE.replace(set, "$set,\"state\":\"x\"") to "state",
        ).forEach { (file, detail) ->
            val error = failed(withAlgorithmFiles(mapOf(path to file)))
            assertInstanceOf(ContentLoadError.Malformed::class.java, error, file)
            assertEquals(path, (error as ContentLoadError.Malformed).path)
            assertTrue(detail in error.detail, error.detail)
        }
        val manifest = """{"schemaVersion":1,"stages":[]}"""
        val error = failed(ContentSource { if (it == ContentLoader.MANIFEST) manifest.toByteArray() else null })
        assertInstanceOf(ContentLoadError.Malformed::class.java, error, error.toString())
        assertEquals(ContentLoader.MANIFEST, (error as ContentLoadError.Malformed).path)
        assertTrue("algorithms" in error.detail, error.detail)
    }

    private companion object {
        const val ALGORITHM_FILE =
            "{\"cases\":[{\"id\":\"case-oll-45\",\"set\":\"OLL\",\"family\":\"oll-t\",\"name\":\"case.case-oll-45.name\"}]," +
                "\"algorithms\":[{\"id\":\"alg-oll-45-a\",\"caseId\":\"case-oll-45\",\"notation\":\"F R U R' U' F'\"}]}"
        val REVISION = Regex("[0-9a-f]{64}")
        const val ADAPTIVE_JSON =
            "\"adaptive\":{\"explainAfterMistakes\":1,\"demonstrateAfterMistakes\":2,\"guideAfterMistakes\":3,\"reduceAfterCleanSteps\":2}"
        const val MASTERY_JSON =
            "\"mastery\":{\"solvesRequired\":2,\"reducedHelpRequired\":1}"
        val SHIPPED_CONFIG =
            TutorConfig(
                maxDepth = 2,
                moveSet = "U U' U2 D D' D2 R R' R2 L L' L2 F F' F2 B B' B2",
                recoveryMaxDepth = 5,
                adaptive = AdaptiveThresholds(1, 2, 3, 2),
                mastery = MasteryThresholds(2, 1),
            )
    }
}
