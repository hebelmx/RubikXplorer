package dev.rubikxplorer.application.usecase

import dev.rubikxplorer.application.port.RandomSource
import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.LessonId
import dev.rubikxplorer.domain.content.RecoveryRef
import dev.rubikxplorer.domain.content.StageId
import dev.rubikxplorer.domain.content.Step
import dev.rubikxplorer.domain.content.TextKey
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.predicate.AllOf
import dev.rubikxplorer.domain.predicate.AlwaysTrue
import dev.rubikxplorer.domain.predicate.CubeSolved
import dev.rubikxplorer.domain.predicate.LastLayerOriented
import dev.rubikxplorer.domain.predicate.MiddleLayerSolved
import dev.rubikxplorer.domain.predicate.Predicate
import dev.rubikxplorer.domain.predicate.WhiteCornersSolved
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved

/**
 * Generates an independent practice [Lesson] for an unlocked Stage (FR-4, FR-20, AD-11, AR-7).
 *
 * Entry states for Stages 2–7 are generated without a Solver:
 * - Stages 2 and 7: 25-move [NewScramble] from [CubeState.SOLVED].
 * - Stage 3: Start SOLVED, apply random sequences that preserve white cross (`D`, `D'`, `D2` and corner pop/insert moves `R' D' R`, `F D F'`, `L D L'`, `B D B'`).
 * - Stage 4: Start SOLVED, apply random sequences that preserve first layer (`D`, `D'`, `D2` and middle-layer insertion sequences `D R D' R' D' F' D F`, `D' L' D L D F D' F'`).
 * - Stage 5: Start SOLVED, apply random sequences that preserve F2L (`D`, `D'`, `D2` and LL orientation algorithms `F L D L' D' F'`, `L D L' D L D2 L'`).
 * - Stage 6: Start SOLVED, apply random sequences that preserve F2L and LL orientation (`D`, `D'`, `D2` and LL permutation algorithms `D L D' R' D L' D' R`, `L D' L D L D L D' L' D' L2`).
 *
 * Scramble generation ensures the earlier Stage predicates hold on entry while the Stage's own completion predicate does not hold.
 */
open class StagePractice(
    private val random: RandomSource,
    private val newScramble: NewScramble = NewScramble(random),
) {
    operator fun invoke(stageId: StageId): Lesson = practiceLesson(stageId)

    open fun practiceLesson(stageId: StageId): Lesson {
        require(stageId != STAGE_1_ID) { "Stage 1 does not support independent practice" }
        val config = CONFIGS[stageId] ?: throw IllegalArgumentException("Unknown stage for practice: $stageId")
        val state = generateScramble(stageId, config)
        check(config.earlierPredicate.isSatisfied(state)) {
            "Earlier stage predicate violated for stage $stageId"
        }
        check(!config.completionPredicate.isSatisfied(state)) {
            "Stage completion predicate already met for stage $stageId"
        }
        val objective = config.objectiveKey
        return Lesson(
            id = LessonId("practice-${stageId.value}"),
            stageId = stageId,
            prerequisites = emptyList(),
            objective = objective,
            entryState = FaceletString.encode(state.toFacelets()),
            demonstration = "",
            learnerAction = objective,
            completion = config.completionPredicate,
            preserve = config.preservePredicate,
            recovery = RecoveryRef("recovery-undo-last-move"),
            steps =
                listOf(
                    Step(
                        goal = objective,
                        preserve = TextKey(""),
                        watch = TextKey(""),
                        notice = TextKey(""),
                        moves = "",
                        done = config.completionPredicate,
                    ),
                ),
        )
    }

    open fun skipCheckLesson(stageId: StageId): Lesson {
        val config =
            if (stageId == STAGE_1_ID) {
                STAGE_1_CONFIG
            } else {
                CONFIGS[stageId] ?: throw IllegalArgumentException("Unknown stage for skip check: $stageId")
            }
        val state = generateScramble(stageId, config)
        check(config.earlierPredicate.isSatisfied(state)) {
            "Earlier stage predicate violated for stage $stageId"
        }
        check(!config.completionPredicate.isSatisfied(state)) {
            "Stage completion predicate already met for stage $stageId"
        }
        val objective = config.objectiveKey
        return Lesson(
            id = LessonId("skip-check-${stageId.value}"),
            stageId = stageId,
            prerequisites = emptyList(),
            objective = objective,
            entryState = FaceletString.encode(state.toFacelets()),
            demonstration = "",
            learnerAction = objective,
            completion = config.completionPredicate,
            preserve = config.preservePredicate,
            recovery = RecoveryRef("recovery-undo-last-move"),
            steps =
                listOf(
                    Step(
                        goal = objective,
                        preserve = TextKey(""),
                        watch = TextKey(""),
                        notice = TextKey(""),
                        moves = "",
                        done = config.completionPredicate,
                    ),
                ),
        )
    }

    open fun courseSkipCheckLesson(): Lesson {
        val config = CONFIGS[STAGE_7_ID] ?: error("Missing config for stage 7")
        var state: CubeState
        var attempts = 0
        do {
            state = CubeState.SOLVED.apply(newScramble())
            attempts++
            check(attempts < MAX_REDRAW_ATTEMPTS) { "Failed to generate valid course skip check scramble after $attempts attempts" }
        } while (CubeSolved.isSatisfied(state))
        val objective = config.objectiveKey
        return Lesson(
            id = LessonId("skip-check-course"),
            stageId = STAGE_7_ID,
            prerequisites = emptyList(),
            objective = objective,
            entryState = FaceletString.encode(state.toFacelets()),
            demonstration = "",
            learnerAction = objective,
            completion = CubeSolved,
            preserve = AlwaysTrue,
            recovery = RecoveryRef("recovery-undo-last-move"),
            steps =
                listOf(
                    Step(
                        goal = objective,
                        preserve = TextKey(""),
                        watch = TextKey(""),
                        notice = TextKey(""),
                        moves = "",
                        done = CubeSolved,
                    ),
                ),
        )
    }

    private fun generateScramble(
        stageId: StageId,
        config: StageConfig,
    ): CubeState {
        var state: CubeState
        var attempts = 0
        do {
            state =
                when (stageId) {
                    STAGE_1_ID -> {
                        val drawn = STAGE_1_MOVES[draw(STAGE_1_MOVES.size)]
                        CubeState.SOLVED.apply(drawn)
                    }

                    STAGE_2_ID, STAGE_7_ID -> {
                        CubeState.SOLVED.apply(newScramble())
                    }

                    STAGE_3_ID -> {
                        applyRandomSequences(STAGE_3_MOVES)
                    }

                    STAGE_4_ID -> {
                        applyRandomSequences(STAGE_4_MOVES)
                    }

                    STAGE_5_ID -> {
                        applyRandomSequences(STAGE_5_MOVES)
                    }

                    STAGE_6_ID -> {
                        applyRandomSequences(STAGE_6_MOVES)
                    }

                    else -> {
                        error("Unsupported stage $stageId")
                    }
                }
            attempts++
            check(attempts < MAX_REDRAW_ATTEMPTS) { "Failed to generate valid practice scramble after $attempts attempts" }
        } while (config.completionPredicate.isSatisfied(state))
        return state
    }

    private fun applyRandomSequences(sequences: List<List<Move>>): CubeState {
        var state = CubeState.SOLVED
        repeat(SCRAMBLE_STEPS) {
            val drawn = sequences[draw(sequences.size)]
            state = state.apply(drawn)
        }
        return state
    }

    private fun draw(until: Int): Int {
        val value = random.nextInt(until)
        check(value in 0 until until) { "RandomSource.nextInt($until) returned $value" }
        return value
    }

    private data class StageConfig(
        val earlierPredicate: Predicate,
        val completionPredicate: Predicate,
        val preservePredicate: Predicate,
        val objectiveKey: TextKey,
    )

    companion object {
        val STAGE_1_ID = StageId("stage-01-fundamentals")
        val STAGE_2_ID = StageId("stage-02-first-cross")
        val STAGE_3_ID = StageId("stage-03-first-layer-corners")
        val STAGE_4_ID = StageId("stage-04-middle-layer")
        val STAGE_5_ID = StageId("stage-05-last-layer-orientation")
        val STAGE_6_ID = StageId("stage-06-last-layer-completion")
        val STAGE_7_ID = StageId("stage-07-independent-practice")

        private const val SCRAMBLE_STEPS = 20
        private const val MAX_REDRAW_ATTEMPTS = 100

        private fun seq(vararg tokens: String): List<Move> =
            tokens.map { token ->
                (Notation.parse(token) as? NotationResult.Parsed)?.moves?.singleOrNull()
                    ?: error("Failed to parse single move: $token")
            }

        private val STAGE_1_MOVES =
            listOf(
                seq("U"),
                seq("U'"),
                seq("R"),
                seq("R'"),
                seq("F"),
                seq("F'"),
            )

        private val STAGE_3_MOVES =
            listOf(
                seq("D"),
                seq("D'"),
                seq("D2"),
                seq("R'", "D'", "R"),
                seq("F", "D", "F'"),
                seq("L", "D", "L'"),
                seq("B", "D", "B'"),
            )

        private val STAGE_4_MOVES =
            listOf(
                seq("D"),
                seq("D'"),
                seq("D2"),
                seq("D'", "R'", "D", "R", "D", "F", "D'", "F'"),
                seq("D", "L", "D'", "L'", "D'", "F'", "D", "F"),
            )

        private val STAGE_5_MOVES =
            listOf(
                seq("D"),
                seq("D'"),
                seq("D2"),
                seq("F", "L", "D", "L'", "D'", "F'"),
                seq("L", "D", "L'", "D", "L", "D2", "L'"),
            )

        private val STAGE_6_MOVES =
            listOf(
                seq("D"),
                seq("D'"),
                seq("D2"),
                seq("L", "D'", "L", "D", "L", "D", "L", "D'", "L'", "D'", "L2"),
                seq("D", "L", "D'", "L", "D", "L", "D", "L", "D'", "L'", "D'", "L2", "D'"),
                seq("D'", "L", "D'", "L", "D", "L", "D", "L", "D'", "L'", "D'", "L2", "D"),
                seq("D2", "L", "D'", "L", "D", "L", "D", "L", "D'", "L'", "D'", "L2", "D2"),
                seq("L2", "D", "L", "D", "L'", "D'", "L'", "D'", "L'", "D", "L'"),
            )

        private val STAGE_1_CONFIG =
            StageConfig(
                earlierPredicate = AlwaysTrue,
                completionPredicate = CubeSolved,
                preservePredicate = AlwaysTrue,
                objectiveKey = TextKey("msg.skip-check.stage-01-fundamentals.objective"),
            )

        private val CONFIGS =
            mapOf(
                STAGE_2_ID to
                    StageConfig(
                        earlierPredicate = AlwaysTrue,
                        completionPredicate = WhiteCrossSolved,
                        preservePredicate = AlwaysTrue,
                        objectiveKey = TextKey("msg.practice.stage-02-first-cross.objective"),
                    ),
                STAGE_3_ID to
                    StageConfig(
                        earlierPredicate = WhiteCrossSolved,
                        completionPredicate = AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved)),
                        preservePredicate = WhiteCrossSolved,
                        objectiveKey = TextKey("msg.practice.stage-03-first-layer-corners.objective"),
                    ),
                STAGE_4_ID to
                    StageConfig(
                        earlierPredicate = AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved)),
                        completionPredicate = AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved, MiddleLayerSolved)),
                        preservePredicate = AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved)),
                        objectiveKey = TextKey("msg.practice.stage-04-middle-layer.objective"),
                    ),
                STAGE_5_ID to
                    StageConfig(
                        earlierPredicate = AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved, MiddleLayerSolved)),
                        completionPredicate = LastLayerOriented,
                        preservePredicate = AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved, MiddleLayerSolved)),
                        objectiveKey = TextKey("msg.practice.stage-05-last-layer-orientation.objective"),
                    ),
                STAGE_6_ID to
                    StageConfig(
                        earlierPredicate = AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved, MiddleLayerSolved, LastLayerOriented)),
                        completionPredicate = CubeSolved,
                        preservePredicate = AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved, MiddleLayerSolved, LastLayerOriented)),
                        objectiveKey = TextKey("msg.practice.stage-06-last-layer-completion.objective"),
                    ),
                STAGE_7_ID to
                    StageConfig(
                        earlierPredicate = AlwaysTrue,
                        completionPredicate = CubeSolved,
                        preservePredicate = AlwaysTrue,
                        objectiveKey = TextKey("msg.practice.stage-07-independent-practice.objective"),
                    ),
            )
    }
}
