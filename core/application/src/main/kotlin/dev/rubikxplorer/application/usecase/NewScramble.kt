package dev.rubikxplorer.application.usecase

import dev.rubikxplorer.application.port.RandomSource
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Turn

/**
 * Generates a random Scramble (FR-4, FR-20): exactly [LENGTH] face turns, applied from home
 * (`CubeState.SOLVED`), drawn through the injected [RandomSource] and returned as explicit Moves (AD-11).
 * No Move cancels or merges with its neighbors: a face never follows itself, and a face is never drawn
 * when the two previous Moves are both on its axis.
 */
class NewScramble(
    private val random: RandomSource,
) {
    operator fun invoke(): List<Move> {
        val moves = ArrayList<FaceTurn>(LENGTH)
        repeat(LENGTH) {
            val previous = moves.lastOrNull()?.face
            val beforePrevious = moves.getOrNull(moves.size - 2)?.face
            val axisClosed = previous != null && beforePrevious == previous.opposite
            val allowed =
                FACES.filter { face ->
                    face != previous && !(axisClosed && face == beforePrevious)
                }
            moves += FaceTurn(allowed[draw(allowed.size)], TURNS[draw(TURNS.size)])
        }
        return moves.toList()
    }

    private fun draw(until: Int): Int {
        val value = random.nextInt(until)
        check(value in 0 until until) { "RandomSource.nextInt($until) returned $value" }
        return value
    }

    companion object {
        /** Face turns in every Scramble, with no adjacent cancellations or merges. */
        const val LENGTH = 25

        private val FACES = Face.entries
        private val TURNS = Turn.entries
    }
}
