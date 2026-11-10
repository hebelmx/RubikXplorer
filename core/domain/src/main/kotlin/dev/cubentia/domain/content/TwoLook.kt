package dev.cubentia.domain.content

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.lastlayer.CaseStateResult
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.predicate.Predicate
import kotlinx.serialization.Serializable

@Serializable
data class TwoLookStage(
    val id: StageId,
    val title: TextKey,
    val lessons: List<TwoLookLesson>,
)

@Serializable
data class TwoLookLesson(
    val id: LessonId,
    val stageId: StageId,
    val prerequisites: List<LessonId>,
    val objective: TextKey,
    val learnerAction: TextKey,
    val completion: Predicate,
    val preserve: Predicate,
    val recovery: RecoveryRef,
    val steps: List<TwoLookStep>,
)

@Serializable
data class TwoLookStep(
    val goal: TextKey,
    val preserve: TextKey,
    val watch: TextKey,
    val notice: TextKey,
    val algorithm: AlgorithmId,
    val done: Predicate,
)

/** Resolves authored Algorithm references without duplicating Library Notation or Case states. */
object TwoLookResolver {
    data class Resolution(
        val stages: List<Stage>,
        val lessonAlgorithms: Map<LessonId, List<AlgorithmId>>,
        val problems: List<ContentProblem>,
    )

    fun resolve(
        stages: List<TwoLookStage>,
        algorithms: List<Algorithm>,
    ): Resolution {
        val library = algorithms.associateBy { it.id }
        val problems = mutableListOf<ContentProblem>()
        val references = mutableMapOf<LessonId, List<AlgorithmId>>()
        val resolved =
            stages.map { stage ->
                Stage(
                    stage.id,
                    stage.title,
                    stage.lessons.mapNotNull { lesson ->
                        references[lesson.id] = lesson.steps.map { it.algorithm }.distinct()
                        val steps =
                            lesson.steps.mapNotNull { step ->
                                val algorithm = library[step.algorithm]
                                if (algorithm == null) {
                                    problems += ContentProblem.UnknownAlgorithm(lesson.id, step.algorithm)
                                    null
                                } else {
                                    Step(step.goal, step.preserve, step.watch, step.notice, algorithm.notation, step.done)
                                }
                            }
                        if (steps.size != lesson.steps.size) return@mapNotNull null
                        val notation = steps.joinToString(" ") { it.moves }
                        when (val entry = LastLayerMatcher.caseState(notation, Color.YELLOW)) {
                            is CaseStateResult.BadNotation -> {
                                problems += ContentProblem.BadNotation(lesson.id, ContentValidator.DEMONSTRATION_FIELD, entry.error)
                                null
                            }

                            is CaseStateResult.Derived -> {
                                Lesson(
                                    lesson.id,
                                    lesson.stageId,
                                    lesson.prerequisites,
                                    lesson.objective,
                                    FaceletString.encode(entry.state.toFacelets()),
                                    notation,
                                    lesson.learnerAction,
                                    lesson.completion,
                                    lesson.preserve,
                                    lesson.recovery,
                                    steps,
                                )
                            }
                        }
                    },
                )
            }
        return Resolution(resolved, references, problems)
    }
}
