package dev.rubikxplorer.domain.content

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.CubeValidator
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.cube.ValidationResult

/**
 * The rules a decoded [ContentBundle] must meet beyond its JSON shape. Pure; shared by
 * [ContentLoader] and the content test. Problems are reported in content order; empty means valid.
 */
object ContentValidator {
    /** Field name of a Lesson's Demonstration, as reported by [ContentProblem.BadNotation]. */
    const val DEMONSTRATION_FIELD = "demonstration"

    /** The `tutor-config.json` field naming the enumeration depth, as reported by [ContentProblem.InvalidTutorConfig]. */
    const val MAX_DEPTH_FIELD = "maxDepth"

    /** The `tutor-config.json` field naming the enumerated Moves, as reported by [ContentProblem.InvalidTutorConfig]. */
    const val MOVE_SET_FIELD = "moveSet"

    /** The enumeration depths the tutor supports: deeper sequences are not bounded work for the reducer. */
    val MAX_DEPTH_RANGE: IntRange = 1..3

    fun validate(bundle: ContentBundle): List<ContentProblem> {
        val problems = mutableListOf<ContentProblem>()
        val lessons = bundle.stages.flatMap { it.lessons }
        val known = lessons.map { it.id }.toSet()

        duplicates(bundle.stages.map { it.id }).mapTo(problems) { ContentProblem.DuplicateStageId(it) }
        duplicates(lessons.map { it.id }).mapTo(problems) { ContentProblem.DuplicateLessonId(it) }

        bundle.stages.forEach { stage ->
            if (!stage.id.isWellFormed) problems += ContentProblem.MalformedStageId(stage.id)
            problems += textProblems(stage.id.value, listOf(stage.title), bundle.strings)
            stage.lessons.forEach { problems += lessonProblems(stage, it, known, bundle.strings) }
        }

        bundle.strings.forEach { (key, message) ->
            val parsed = IcuArguments.parse(message)
            if (parsed is IcuResult.Malformed) problems += ContentProblem.MalformedMessage(key, parsed.index)
        }
        problems += tutorConfigProblems(bundle.tutorConfig)
        return problems
    }

    /** [config]'s `maxDepth` must lie in [MAX_DEPTH_RANGE]; its `moveSet` must be valid, non-empty Notation of distinct face turns. */
    private fun tutorConfigProblems(config: TutorConfig): List<ContentProblem> {
        val problems = mutableListOf<ContentProblem>()
        if (config.maxDepth !in MAX_DEPTH_RANGE) {
            problems += ContentProblem.InvalidTutorConfig(MAX_DEPTH_FIELD, ContentProblem.InvalidTutorConfig.Detail.OUT_OF_RANGE)
        }
        when (val parsed = Notation.parse(config.moveSet)) {
            is NotationResult.Invalid -> {
                problems += ContentProblem.InvalidTutorConfig(MOVE_SET_FIELD, ContentProblem.InvalidTutorConfig.Detail.BAD_NOTATION)
            }

            is NotationResult.Parsed -> {
                if (parsed.moves.isEmpty()) {
                    problems +=
                        ContentProblem.InvalidTutorConfig(MOVE_SET_FIELD, ContentProblem.InvalidTutorConfig.Detail.NO_MOVES)
                }
                // Non-face-turn Moves and repeated Moves give several ways to one state, which would leave a
                // one-move mistake ambiguous.
                if (parsed.moves.any { it !is FaceTurn }) {
                    problems +=
                        ContentProblem.InvalidTutorConfig(MOVE_SET_FIELD, ContentProblem.InvalidTutorConfig.Detail.NOT_FACE_TURN)
                }
                if (parsed.moves.toSet().size != parsed.moves.size) {
                    problems +=
                        ContentProblem.InvalidTutorConfig(MOVE_SET_FIELD, ContentProblem.InvalidTutorConfig.Detail.DUPLICATE_MOVE)
                }
            }
        }
        return problems
    }

    private fun lessonProblems(
        stage: Stage,
        lesson: Lesson,
        known: Set<LessonId>,
        strings: Map<String, String>,
    ): List<ContentProblem> {
        val problems = mutableListOf<ContentProblem>()
        val id = lesson.id
        if (!id.isWellFormed) problems += ContentProblem.MalformedLessonId(id)
        if (lesson.stageId != stage.id) problems += ContentProblem.StageMismatch(id, lesson.stageId, stage.id)
        if (!lesson.recovery.isWellFormed) problems += ContentProblem.MalformedRecoveryRef(id, lesson.recovery)
        lesson.prerequisites
            .filterNot { it in known }
            .mapTo(problems) { ContentProblem.UnknownPrerequisite(id, it) }

        notationProblem(id, DEMONSTRATION_FIELD, lesson.demonstration)?.let { problems += it }
        lesson.steps.forEachIndexed { index, step ->
            notationProblem(id, stepMovesField(index), step.moves)?.let { problems += it }
        }
        entryStateProblem(lesson)?.let { problems += it }
        problems += stepProblems(lesson)

        val keys =
            listOf(lesson.objective, lesson.learnerAction) +
                lesson.steps.flatMap { listOf(it.goal, it.preserve, it.watch, it.notice) }
        problems += textProblems(id.value, keys, strings)
        return problems
    }

    /** The field name [ContentProblem.BadNotation] reports for the Moves of step [index] (0-based). */
    fun stepMovesField(index: Int): String = "steps.$index.moves"

    private fun notationProblem(
        lesson: LessonId,
        field: String,
        text: String,
    ): ContentProblem? =
        when (val parsed = Notation.parse(text)) {
            is NotationResult.Parsed -> null
            is NotationResult.Invalid -> ContentProblem.BadNotation(lesson, field, parsed.error)
        }

    /**
     * The path rules: a Lesson has steps; each step has Moves; each step changes the Cube State (judged
     * by [CubeState] equality, as `Tutor.currentStep` matches the authored path, and only when the entry
     * state and every step parse); and the Demonstration is the steps' Moves in order (only when all parse).
     */
    private fun stepProblems(lesson: Lesson): List<ContentProblem> {
        val id = lesson.id
        if (lesson.steps.isEmpty()) return listOf(ContentProblem.NoSteps(id))
        val problems = mutableListOf<ContentProblem>()
        val parsed = lesson.steps.map { (Notation.parse(it.moves) as? NotationResult.Parsed)?.moves }
        parsed.forEachIndexed { index, moves -> if (moves?.isEmpty() == true) problems += ContentProblem.EmptyStep(id, index) }
        val parsedSteps = parsed.filterNotNull().takeIf { it.size == parsed.size } ?: return problems

        val entry = entryState(lesson)
        if (entry != null) {
            var state: CubeState = entry
            parsedSteps.forEachIndexed { index, moves ->
                val next = state.apply(moves)
                if (moves.isNotEmpty() && next == state) problems += ContentProblem.NoOpStep(id, index)
                state = next
            }
        }

        val demonstration = (Notation.parse(lesson.demonstration) as? NotationResult.Parsed)?.moves
        if (demonstration != null && demonstration != parsedSteps.flatten()) {
            problems += ContentProblem.DemonstrationMismatch(id)
        }
        return problems
    }

    /** The validated entry [CubeState] of [lesson], or null when it is malformed or impossible. */
    private fun entryState(lesson: Lesson): CubeState? {
        val decoded = FaceletString.decode(lesson.entryState) as? FaceletStringResult.Decoded ?: return null
        return (CubeValidator.validate(decoded.facelets) as? ValidationResult.Valid)?.state
    }

    private fun entryStateProblem(lesson: Lesson): ContentProblem? =
        when (val decoded = FaceletString.decode(lesson.entryState)) {
            is FaceletStringResult.Decoded -> {
                when (val result = CubeValidator.validate(decoded.facelets)) {
                    is ValidationResult.Valid -> null
                    is ValidationResult.Invalid -> ContentProblem.ImpossibleEntryState(lesson.id, result)
                }
            }

            else -> {
                ContentProblem.MalformedEntryState(lesson.id, decoded)
            }
        }

    private fun textProblems(
        owner: String,
        keys: List<TextKey>,
        strings: Map<String, String>,
    ): List<ContentProblem> =
        keys.mapNotNull { key ->
            val message = strings[key.value]
            when {
                key.contentId != owner -> {
                    ContentProblem.MalformedTextKey(owner, key)
                }

                message == null -> {
                    ContentProblem.MissingTextKey(key)
                }

                else -> {
                    val parsed = IcuArguments.parse(message)
                    if (parsed is IcuResult.Parsed && parsed.arguments.isNotEmpty()) {
                        ContentProblem.TextKeyTakesArguments(key, parsed.arguments)
                    } else {
                        null
                    }
                }
            }
        }

    private fun <T> duplicates(ids: List<T>): Set<T> =
        ids
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
}
