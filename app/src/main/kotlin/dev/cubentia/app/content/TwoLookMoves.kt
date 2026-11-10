package dev.cubentia.app.content

import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult

/** The LL practice's content-derived 2-look Algorithm set: every Algorithm a 2-look Lesson teaches, as Moves. */
fun ContentBundle.twoLookMoves(): List<List<Move>> {
    val ids =
        twoLookStages
            .flatMap { it.lessons }
            .flatMap { lessonAlgorithms[it.id].orEmpty() }
            .toSet()
    return algorithms
        .filter { it.id in ids }
        .map { (Notation.parse(it.notation) as NotationResult.Parsed).moves }
}
