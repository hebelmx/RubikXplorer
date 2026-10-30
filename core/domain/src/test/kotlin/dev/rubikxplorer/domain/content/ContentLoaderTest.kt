package dev.rubikxplorer.domain.content

import dev.rubikxplorer.domain.cube.CubeValidator
import dev.rubikxplorer.domain.cube.NotationError
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.cube.ValidationResult
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.rubikxplorer.domain.cube.Notation as CubeNotation

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
        val revisions =
            listOf(ContentLoader.STRINGS, "stages/stage-01-basics.json", ContentLoader.MANIFEST).map { changed ->
                val touched =
                    ContentSource { path ->
                        valid.read(path)?.let { if (path == changed) it + ' '.code.toByte() else it }
                    }
                loaded(touched).contentRevision.also { assertNotEquals(first, it, changed) }
            }
        assertEquals(3, revisions.toSet().size)
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

    private companion object {
        val REVISION = Regex("[0-9a-f]{64}")
    }
}
