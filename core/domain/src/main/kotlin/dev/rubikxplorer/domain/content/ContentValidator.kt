package dev.rubikxplorer.domain.content

import dev.rubikxplorer.domain.cube.CubeValidator
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
