package dev.rubikxplorer.domain.content

import dev.rubikxplorer.domain.cube.NotationError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** AC 1: each [ContentValidator] rule on a bundle built from the valid fixture. */
class ContentValidatorTest {
    private val valid: ContentBundle = (ContentLoader.load(fixture("valid")) as ContentLoadResult.Loaded).bundle
    private val stage: Stage = valid.stages.single()
    private val lesson: Lesson = stage.lessons.single()

    private fun withLesson(change: Lesson.() -> Lesson): ContentBundle =
        valid.copy(stages = listOf(stage.copy(lessons = listOf(lesson.change()))))

    private val id = lesson.id

    @Test
    fun `duplicate and malformed Stage ids`() {
        val renamed = stage.copy(id = StageId("stage-1-basics"), title = TextKey("stage.stage-1-basics.title"))
        val bundle =
            valid.copy(
                stages = listOf(stage, stage.copy(lessons = emptyList()), renamed.copy(lessons = emptyList())),
                strings = valid.strings + ("stage.stage-1-basics.title" to "Fixture"),
            )
        assertEquals(
            listOf(ContentProblem.DuplicateStageId(stage.id), ContentProblem.MalformedStageId(StageId("stage-1-basics"))),
            ContentValidator.validate(bundle),
        )
    }

    @Test
    fun `a malformed Lesson id also breaks its owned keys`() {
        val problems = ContentValidator.validate(withLesson { copy(id = LessonId("lesson-1-1")) })
        assertEquals(ContentProblem.MalformedLessonId(LessonId("lesson-1-1")), problems.first())
        assertEquals(1 + 6, problems.size)
    }

    @Test
    fun `stageId must name the containing Stage`() {
        assertEquals(
            listOf(ContentProblem.StageMismatch(id, StageId("stage-02-other"), stage.id)),
            ContentValidator.validate(withLesson { copy(stageId = StageId("stage-02-other")) }),
        )
    }

    @Test
    fun `prerequisites must name known Lessons`() {
        assertEquals(
            listOf(ContentProblem.UnknownPrerequisite(id, LessonId("lesson-00-01"))),
            ContentValidator.validate(withLesson { copy(prerequisites = listOf(id, LessonId("lesson-00-01"))) }),
        )
    }

    @Test
    fun `recovery must be a recovery id`() {
        assertEquals(
            listOf(ContentProblem.MalformedRecoveryRef(id, RecoveryRef("undo"))),
            ContentValidator.validate(withLesson { copy(recovery = RecoveryRef("undo")) }),
        )
    }

    @Test
    fun `step Moves must parse`() {
        val step = lesson.steps.single()
        assertEquals(
            listOf(ContentProblem.BadNotation(id, "steps.1.moves", NotationError.BadToken("F3", 0))),
            ContentValidator.validate(withLesson { copy(steps = listOf(step, step.copy(moves = "F3"))) }),
        )
    }

    @Test
    fun `entry state must be 54 known letters`() {
        assertEquals(
            listOf(ContentProblem.MalformedEntryState(id, FaceletStringResult.WrongLength(3))),
            ContentValidator.validate(withLesson { copy(entryState = "WWW") }),
        )
        assertEquals(
            listOf(ContentProblem.MalformedEntryState(id, FaceletStringResult.BadLetter(0, 'w'))),
            ContentValidator.validate(withLesson { copy(entryState = "w" + entryState.drop(1)) }),
        )
    }

    @Test
    fun `text keys must have the key form and exist`() {
        assertEquals(
            listOf(ContentProblem.MalformedTextKey(id.value, TextKey("Objective"))),
            ContentValidator.validate(withLesson { copy(objective = TextKey("Objective")) }),
        )
        val titleless = valid.copy(strings = valid.strings - "stage.stage-01-basics.title")
        assertEquals(listOf(ContentProblem.MissingTextKey(stage.title)), ContentValidator.validate(titleless))
    }

    @Test
    fun `every message must be well-formed ICU`() {
        val broken = valid.copy(strings = valid.strings + ("msg.fixture.broken" to "Turn {face"))
        assertEquals(listOf(ContentProblem.MalformedMessage("msg.fixture.broken", 5)), ContentValidator.validate(broken))
    }

    @Test
    fun `a Lesson needs at least one step`() {
        assertEquals(listOf(ContentProblem.NoSteps(id)), ContentValidator.validate(withLesson { copy(steps = emptyList()) }))
    }

    @Test
    fun `a step whose Moves parse to nothing is empty`() {
        val step = lesson.steps.single()
        assertEquals(
            listOf(ContentProblem.EmptyStep(id, 1)),
            ContentValidator.validate(withLesson { copy(steps = listOf(step, step.copy(moves = " "))) }),
        )
    }

    @Test
    fun `a step that ends on the state it started from is a no-op`() {
        val step = lesson.steps.single()
        assertEquals(
            listOf(ContentProblem.NoOpStep(id, 0)),
            ContentValidator.validate(withLesson { copy(demonstration = "R R' F'", steps = listOf(step.copy(moves = "R R'"), step)) }),
        )
        // A whole-cube rotation changes the orientation, so the path state differs, as Tutor.decide sees it.
        assertEquals(
            emptyList<ContentProblem>(),
            ContentValidator.validate(withLesson { copy(demonstration = "y F'", steps = listOf(step.copy(moves = "y"), step)) }),
        )
    }

    @Test
    fun `the no-op rule waits for a valid entry state`() {
        val step = lesson.steps.single()
        assertEquals(
            listOf(ContentProblem.MalformedEntryState(id, FaceletStringResult.WrongLength(3))),
            ContentValidator.validate(
                withLesson { copy(entryState = "WWW", demonstration = "R R'", steps = listOf(step.copy(moves = "R R'"))) },
            ),
        )
    }

    @Test
    fun `an unparseable step suppresses the no-op and Demonstration checks`() {
        val step = lesson.steps.single()
        assertEquals(
            listOf(
                ContentProblem.BadNotation(id, ContentValidator.DEMONSTRATION_FIELD, NotationError.BadToken("F3", 0)),
                ContentProblem.BadNotation(id, "steps.0.moves", NotationError.BadToken("F3", 0)),
            ),
            ContentValidator.validate(
                withLesson { copy(demonstration = "F3 R R'", steps = listOf(step.copy(moves = "F3"), step.copy(moves = "R R'"))) },
            ),
        )
    }

    @Test
    fun `the Demonstration must be the steps' Moves in order`() {
        assertEquals(
            listOf(ContentProblem.DemonstrationMismatch(id)),
            ContentValidator.validate(withLesson { copy(demonstration = "F") }),
        )
        assertEquals(
            listOf(ContentProblem.DemonstrationMismatch(id)),
            ContentValidator.validate(withLesson { copy(demonstration = "F' R") }),
        )
    }

    @Test
    fun `equivalent Notation in the Demonstration matches`() {
        val step = lesson.steps.single()
        assertEquals(
            emptyList<ContentProblem>(),
            ContentValidator.validate(withLesson { copy(demonstration = "  F'   R2' ", steps = listOf(step.copy(moves = "F' R2"))) }),
        )
    }
}
