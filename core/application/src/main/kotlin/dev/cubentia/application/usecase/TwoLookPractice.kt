package dev.cubentia.application.usecase

import dev.cubentia.application.port.RandomSource
import dev.cubentia.domain.content.FaceletString
import dev.cubentia.domain.content.Lesson
import dev.cubentia.domain.content.RecoveryRef
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.content.Step
import dev.cubentia.domain.content.TextKey
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.lastlayer.LastLayerStep
import dev.cubentia.domain.lastlayer.LastLayerView
import dev.cubentia.domain.predicate.AllOf
import dev.cubentia.domain.predicate.CubeSolved
import dev.cubentia.domain.predicate.LastLayerStepReached
import dev.cubentia.domain.predicate.MiddleLayerSolved
import dev.cubentia.domain.predicate.WhiteCornersSolved
import dev.cubentia.domain.predicate.WhiteCrossSolved
import dev.cubentia.domain.tutor.TwoLookMastery

class TwoLookPractice(
    private val random: RandomSource,
) {
    fun lesson(scrambleAlgorithms: List<List<Move>>): Lesson {
        require(scrambleAlgorithms.isNotEmpty())
        val frame = LastLayerView.canonicalFrame(Color.YELLOW)
        var state = CubeState.SOLVED.reoriented(frame)
        var redraws = 0
        while (true) {
            state = CubeState.SOLVED.reoriented(frame)
            repeat(20) {
                state =
                    state
                        .apply(scrambleAlgorithms[random.nextInt(scrambleAlgorithms.size)])
                        .reoriented(frame)
                        .apply(FaceTurn(Face.U, Turn.entries[random.nextInt(Turn.entries.size)]))
            }
            if (!LastLayerStepReached(LastLayerStep.SOLVED_UP_TO_AUF).isSatisfied(state)) break
            check(++redraws < 100) { "Could not generate an unsolved last layer" }
        }
        check(PRESERVE.isSatisfied(state))
        return lessonAt(FaceletString.encode(state.toFacelets()))
    }

    companion object {
        private val PRESERVE = AllOf(listOf(WhiteCrossSolved, WhiteCornersSolved, MiddleLayerSolved))

        /** The LL practice Lesson at a known [entryState]: rebuilds a generated scramble after recreation or a cold-start restore. */
        fun lessonAt(entryState: String): Lesson {
            val objective = TextKey("msg.practice.two-look.objective")
            return Lesson(
                id = TwoLookMastery.PRACTICE_ID,
                stageId = StageId("two-look-practice"),
                prerequisites = emptyList(),
                objective = objective,
                entryState = entryState,
                demonstration = "",
                learnerAction = objective,
                completion = CubeSolved,
                preserve = PRESERVE,
                recovery = RecoveryRef("recovery-undo-last-move"),
                steps = listOf(Step(objective, TextKey(""), TextKey(""), TextKey(""), "", CubeSolved)),
            )
        }
    }
}
