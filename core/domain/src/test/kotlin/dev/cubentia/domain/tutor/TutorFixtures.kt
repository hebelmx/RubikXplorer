package dev.cubentia.domain.tutor

import dev.cubentia.domain.content.AdaptiveThresholds
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.FaceletString
import dev.cubentia.domain.content.FaceletStringResult
import dev.cubentia.domain.content.Lesson
import dev.cubentia.domain.content.LessonId
import dev.cubentia.domain.content.MasteryThresholds
import dev.cubentia.domain.content.RecoveryRef
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.content.Step
import dev.cubentia.domain.content.TextKey
import dev.cubentia.domain.content.TutorConfig
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.predicate.AlwaysTrue
import dev.cubentia.domain.predicate.AnyOf
import dev.cubentia.domain.predicate.Piece
import dev.cubentia.domain.predicate.Predicate
import dev.cubentia.domain.predicate.StateReached
import dev.cubentia.domain.predicate.WhiteCrossSolved
import dev.cubentia.domain.predicate.WhitePieceSolved

/** Shared tutor test fixtures: Lessons built in code, with explicit step `done`s. */
internal object TutorFixtures {
    /** The shipped `tutor-config.json` values. */
    val CONFIG =
        TutorConfig(
            maxDepth = 2,
            moveSet = "U U' U2 D D' D2 R R' R2 L L' L2 F F' F2 B B' B2",
            recoveryMaxDepth = 5,
            adaptive = AdaptiveThresholds(1, 2, 3, 2),
            mastery = MasteryThresholds(2, 1),
        )

    fun moves(text: String): List<Move> = (Notation.parse(text) as NotationResult.Parsed).moves

    fun state(text: String): CubeState = CubeState.SOLVED.apply(moves(text))

    /** Every shipped Lesson, in Stage order. */
    val shipped: List<Lesson> by lazy {
        val result = ContentLoader.load(ContentSource.SHIPPED)
        check(result is ContentLoadResult.Loaded) { "the shipped content does not load: $result" }
        result.bundle.stages.flatMap { it.lessons }
    }

    /** The shipped Lesson [id]. */
    fun shipped(id: String): Lesson = shipped.single { it.id.value == id }

    /** The validated entry state of [lesson]. */
    fun entryOf(lesson: Lesson): CubeState {
        val facelets = (FaceletString.decode(lesson.entryState) as FaceletStringResult.Decoded).facelets
        return (CubeValidator.validate(facelets) as ValidationResult.Valid).state
    }

    /** A white-cross Lesson entered at SOLVED + [entry], one step per entry of [steps] (`moves to done`). */
    fun lesson(
        entry: String,
        vararg steps: Pair<String, Predicate>,
        completion: Predicate = WhiteCrossSolved,
    ): Lesson =
        Lesson(
            id = LessonId("lesson-01-01"),
            stageId = StageId("stage-01-basics"),
            prerequisites = emptyList(),
            objective = TextKey("lesson.lesson-01-01.objective"),
            entryState = FaceletString.encode(state(entry).toFacelets()),
            demonstration = steps.joinToString(" ") { it.first },
            learnerAction = TextKey("lesson.lesson-01-01.learner-action"),
            completion = completion,
            preserve = AlwaysTrue, // a first Lesson: nothing earlier to keep (AD-6)
            recovery = RecoveryRef("recovery-undo-last-move"),
            steps =
                steps.mapIndexed { i, (m, done) ->
                    Step(
                        goal = TextKey("lesson.lesson-01-01.goal.${i + 1}"),
                        preserve = TextKey("lesson.lesson-01-01.keep.${i + 1}"),
                        watch = TextKey("lesson.lesson-01-01.watch.${i + 1}"),
                        notice = TextKey("lesson.lesson-01-01.notice.${i + 1}"),
                        moves = m,
                        done = done,
                    )
                },
        )

    /** Entry SOLVED + F, one step `F'`. */
    val oneStep: Lesson = lesson("F", "F'" to WhiteCrossSolved)

    /** Entry SOLVED + F U', step 1 `U` (to SOLVED + F), step 2 `F'`. */
    val twoStep: Lesson = lesson("F U'", "U" to AnyOf(listOf(StateReached("F"), WhiteCrossSolved)), "F'" to WhiteCrossSolved)

    /** Lesson 02-05's shape: four cross edges, one per step, entered at SOLVED + L2 B2 R2 F2. */
    val fourEdges: Lesson =
        lesson(
            "L2 B2 R2 F2",
            "F2" to WhitePieceSolved("white-green"),
            "R2" to WhitePieceSolved("white-red"),
            "B2" to WhitePieceSolved("white-blue"),
            "L2" to WhiteCrossSolved,
        )
}

/** White-layer pieces by their colors, for Diagnosis assertions. */
internal object Pieces {
    val WHITE_GREEN = Piece(setOf(Color.WHITE, Color.GREEN))
    val WHITE_RED = Piece(setOf(Color.WHITE, Color.RED))
    val WHITE_GREEN_RED = Piece(setOf(Color.WHITE, Color.GREEN, Color.RED))
}
