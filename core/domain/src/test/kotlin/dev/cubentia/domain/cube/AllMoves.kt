package dev.cubentia.domain.cube

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list

/** Every Move value of every type: 18 face, 18 wide, 9 slice, 9 rotation. */
internal val allMoves: List<Move> =
    Turn.entries.flatMap { t ->
        Face.entries.map { FaceTurn(it, t) } +
            Face.entries.map { WideTurn(it, t) } +
            Slice.entries.map { SliceTurn(it, t) } +
            Axis.entries.map { CubeRotation(it, t) }
    }

internal val arbAnyMove: Arb<Move> = Arb.element(allMoves)

internal val arbAnySequence: Arb<List<Move>> = Arb.list(arbAnyMove, 0..40)

/** Random states in any orientation, reached by Moves of every type. */
internal val arbAnyState: Arb<CubeState> =
    Arb.bind(Arb.element(Orientation.ALL), arbAnySequence) { o, seq -> CubeState(Cubies.SOLVED, o).apply(seq) }
