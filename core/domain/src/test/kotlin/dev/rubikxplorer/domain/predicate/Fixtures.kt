package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.cube.Orientation
import dev.rubikxplorer.domain.cube.Turn
import org.junit.jupiter.api.Assertions.assertEquals

/** Shared helpers for predicate fixture tests. */
internal object Fixtures {
    val faceTurns: List<FaceTurn> = Turn.entries.flatMap { t -> Face.entries.map { FaceTurn(it, t) } }

    fun moves(text: String): List<Move> = (Notation.parse(text) as NotationResult.Parsed).moves

    fun state(text: String): CubeState = CubeState.SOLVED.apply(moves(text))

    /** A fixed 25-Move Scramble. */
    val scramble: List<Move> = moves("D2 B' R2 U F' L D' B2 R' U2 F L2 D B' R U' L' F2 D' R2 B U L2 F' D")

    /**
     * [p] gives the same answer for [state] in all 24 orientations, and its progress agrees with
     * [Predicate.isSatisfied] (when [completeIffSatisfied]) and with its preserved pieces (when [countsPieces]).
     */
    fun assertSameInEveryOrientation(
        p: Predicate,
        label: String,
        state: CubeState,
        countsPieces: Boolean = true,
        completeIffSatisfied: Boolean = true,
    ) {
        val satisfied = p.isSatisfied(state)
        val progress = p.progress(state)
        val preserved = p.preservedPieces(state)
        if (countsPieces) assertEquals(preserved.size, progress.done, label)
        if (completeIffSatisfied) assertEquals(satisfied, progress.done == progress.total, label)
        Orientation.ALL.forEach { o ->
            val rotated = state.copy(orientation = o)
            assertEquals(satisfied, p.isSatisfied(rotated), "$label at $o")
            assertEquals(progress, p.progress(rotated), "$label at $o")
            assertEquals(preserved, p.preservedPieces(rotated), "$label at $o")
        }
    }
}
