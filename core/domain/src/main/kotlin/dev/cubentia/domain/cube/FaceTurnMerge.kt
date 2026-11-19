package dev.cubentia.domain.cube

/** Combines adjacent turns of the same face until stable, preserving the sequence's effect. */
fun List<FaceTurn>.mergeSameFaceTurns(): List<FaceTurn> = mergeSameFaceTurns {}

/** Combines adjacent same-face turns, calling [checkpoint] after reading each input turn. */
fun List<FaceTurn>.mergeSameFaceTurns(checkpoint: () -> Unit): List<FaceTurn> {
    val merged = mutableListOf<FaceTurn>()
    for (move in this) {
        checkpoint()
        val previous = merged.lastOrNull()
        if (previous?.face == move.face) {
            merged.removeAt(merged.lastIndex)
            val quarters = (previous.turn.quarters + move.turn.quarters) % 4
            if (quarters != 0) {
                merged += FaceTurn(move.face, Turn.entries.first { it.quarters == quarters })
            }
        } else {
            merged += move
        }
    }
    return merged
}
