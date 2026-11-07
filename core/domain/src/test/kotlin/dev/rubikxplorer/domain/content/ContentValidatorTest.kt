package dev.rubikxplorer.domain.content

import dev.rubikxplorer.domain.content.ContentProblem.InvalidTutorConfig.Detail.BAD_NOTATION
import dev.rubikxplorer.domain.content.ContentProblem.InvalidTutorConfig.Detail.DUPLICATE_MOVE
import dev.rubikxplorer.domain.content.ContentProblem.InvalidTutorConfig.Detail.NOT_FACE_TURN
import dev.rubikxplorer.domain.content.ContentProblem.InvalidTutorConfig.Detail.NOT_INCREASING
import dev.rubikxplorer.domain.content.ContentProblem.InvalidTutorConfig.Detail.NO_MOVES
import dev.rubikxplorer.domain.content.ContentProblem.InvalidTutorConfig.Detail.OUT_OF_RANGE
import dev.rubikxplorer.domain.content.ContentProblem.InvalidTutorConfig.Detail.REDUCED_EXCEEDS_SOLVES
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
        // A whole-cube rotation changes the orientation, so the path state differs, as Tutor.currentStep sees it.
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

    @Test
    fun `the tutor config needs maxDepth in 1 to 3 and a non-empty valid moveSet`() {
        fun problems(config: TutorConfig) = ContentValidator.validate(valid.copy(tutorConfig = config))
        assertEquals(emptyList<ContentProblem>(), problems(TutorConfig(1, "U", 1, ADAPTIVE, MASTERY)))
        assertEquals(emptyList<ContentProblem>(), problems(TutorConfig(3, "U R", 8, ADAPTIVE, MASTERY)))
        assertEquals(
            listOf(
                ContentProblem.InvalidTutorConfig(ContentValidator.MAX_DEPTH_FIELD, OUT_OF_RANGE),
                ContentProblem.InvalidTutorConfig(ContentValidator.MOVE_SET_FIELD, NO_MOVES),
            ),
            problems(TutorConfig(0, "", 5, ADAPTIVE, MASTERY)),
        )
        assertEquals(
            listOf(
                ContentProblem.InvalidTutorConfig(ContentValidator.MAX_DEPTH_FIELD, OUT_OF_RANGE),
                ContentProblem.InvalidTutorConfig(ContentValidator.MOVE_SET_FIELD, BAD_NOTATION),
            ),
            problems(TutorConfig(4, "U Q", 5, ADAPTIVE, MASTERY)),
        )
    }

    @Test
    fun `the tutor config needs recoveryMaxDepth in 1 to 8`() {
        fun problems(depth: Int) = ContentValidator.validate(valid.copy(tutorConfig = TutorConfig(2, "U R", depth, ADAPTIVE, MASTERY)))
        listOf(0, 9, -1).forEach { depth ->
            assertEquals(
                listOf(ContentProblem.InvalidTutorConfig(ContentValidator.RECOVERY_MAX_DEPTH_FIELD, OUT_OF_RANGE)),
                problems(depth),
                "recoveryMaxDepth $depth",
            )
        }
        assertEquals(ContentValidator.RECOVERY_MAX_DEPTH_RANGE, 1..8)
    }

    @Test
    fun `the tutor move set holds only face turns`() {
        listOf("U r", "U M", "U x").forEach { moves ->
            assertEquals(
                listOf(ContentProblem.InvalidTutorConfig(ContentValidator.MOVE_SET_FIELD, NOT_FACE_TURN)),
                ContentValidator.validate(valid.copy(tutorConfig = TutorConfig(2, moves, 5, ADAPTIVE, MASTERY))),
                moves,
            )
        }
    }

    @Test
    fun `the tutor move set lists each Move once, compared after parsing`() {
        listOf("U U R", "R2 R2'").forEach { moves ->
            assertEquals(
                listOf(ContentProblem.InvalidTutorConfig(ContentValidator.MOVE_SET_FIELD, DUPLICATE_MOVE)),
                ContentValidator.validate(valid.copy(tutorConfig = TutorConfig(2, moves, 5, ADAPTIVE, MASTERY))),
                moves,
            )
        }
    }

    @Test
    fun `each adaptive threshold lies in 1 to 10, reported as adaptive dot its name`() {
        fun problems(adaptive: AdaptiveThresholds) =
            ContentValidator.validate(valid.copy(tutorConfig = valid.tutorConfig.copy(adaptive = adaptive)))
        assertEquals(emptyList<ContentProblem>(), problems(AdaptiveThresholds(1, 2, 3, 1)))
        assertEquals(emptyList<ContentProblem>(), problems(AdaptiveThresholds(8, 9, 10, 10)))
        val cases =
            mapOf(
                AdaptiveThresholds(0, 2, 3, 2) to "explainAfterMistakes",
                AdaptiveThresholds(1, 2, 11, 2) to "guideAfterMistakes",
                AdaptiveThresholds(1, 2, 3, 0) to "reduceAfterCleanSteps",
                AdaptiveThresholds(1, 2, 3, 11) to "reduceAfterCleanSteps",
            )
        cases.forEach { (adaptive, name) ->
            assertEquals(
                listOf(ContentProblem.InvalidTutorConfig("${ContentValidator.ADAPTIVE_FIELD}.$name", OUT_OF_RANGE)),
                problems(adaptive),
                adaptive.toString(),
            )
        }
        assertEquals(
            listOf(
                ContentProblem.InvalidTutorConfig("adaptive.demonstrateAfterMistakes", OUT_OF_RANGE),
                ContentProblem.InvalidTutorConfig("adaptive.guideAfterMistakes", OUT_OF_RANGE),
            ),
            problems(AdaptiveThresholds(1, 11, 12, 2)),
        )
        assertEquals(ContentValidator.ADAPTIVE_THRESHOLD_RANGE, 1..10)
    }

    @Test
    fun `the adaptive mistake thresholds rise strictly`() {
        fun problems(adaptive: AdaptiveThresholds) =
            ContentValidator.validate(valid.copy(tutorConfig = valid.tutorConfig.copy(adaptive = adaptive)))
        listOf(AdaptiveThresholds(2, 2, 3, 2), AdaptiveThresholds(1, 3, 3, 2), AdaptiveThresholds(3, 2, 1, 2)).forEach { adaptive ->
            assertEquals(
                listOf(ContentProblem.InvalidTutorConfig(ContentValidator.ADAPTIVE_FIELD, NOT_INCREASING)),
                problems(adaptive),
                adaptive.toString(),
            )
        }
    }

    @Test
    fun `mastery thresholds need solvesRequired in 1 to 10 and reducedHelpRequired in 0 to 10`() {
        fun problems(mastery: MasteryThresholds) =
            ContentValidator.validate(valid.copy(tutorConfig = valid.tutorConfig.copy(mastery = mastery)))
        assertEquals(emptyList<ContentProblem>(), problems(MasteryThresholds(1, 0)))
        assertEquals(emptyList<ContentProblem>(), problems(MasteryThresholds(10, 10)))
        assertEquals(
            listOf(ContentProblem.InvalidTutorConfig("${ContentValidator.MASTERY_FIELD}.solvesRequired", OUT_OF_RANGE)),
            problems(MasteryThresholds(0, 0)),
        )
        assertEquals(
            listOf(ContentProblem.InvalidTutorConfig("${ContentValidator.MASTERY_FIELD}.solvesRequired", OUT_OF_RANGE)),
            problems(MasteryThresholds(11, 5)),
        )
        assertEquals(
            listOf(ContentProblem.InvalidTutorConfig("${ContentValidator.MASTERY_FIELD}.reducedHelpRequired", OUT_OF_RANGE)),
            problems(MasteryThresholds(2, -1)),
        )
        assertEquals(
            listOf(
                ContentProblem.InvalidTutorConfig("${ContentValidator.MASTERY_FIELD}.reducedHelpRequired", OUT_OF_RANGE),
                ContentProblem.InvalidTutorConfig(ContentValidator.MASTERY_FIELD, REDUCED_EXCEEDS_SOLVES),
            ),
            problems(MasteryThresholds(5, 11)),
        )
        assertEquals(ContentValidator.MASTERY_SOLVES_RANGE, 1..10)
        assertEquals(ContentValidator.MASTERY_REDUCED_RANGE, 0..10)
    }

    @Test
    fun `mastery reducedHelpRequired cannot exceed solvesRequired`() {
        fun problems(mastery: MasteryThresholds) =
            ContentValidator.validate(valid.copy(tutorConfig = valid.tutorConfig.copy(mastery = mastery)))
        listOf(MasteryThresholds(2, 3), MasteryThresholds(1, 2)).forEach { mastery ->
            assertEquals(
                listOf(ContentProblem.InvalidTutorConfig(ContentValidator.MASTERY_FIELD, REDUCED_EXCEEDS_SOLVES)),
                problems(mastery),
                mastery.toString(),
            )
        }
    }

    private companion object {
        val ADAPTIVE = AdaptiveThresholds(1, 2, 3, 2)
        val MASTERY = MasteryThresholds(2, 1)
    }
}
