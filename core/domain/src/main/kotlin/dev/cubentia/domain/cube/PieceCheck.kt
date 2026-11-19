package dev.cubentia.domain.cube

import dev.cubentia.domain.text.Text

/**
 * One review message: [positions] are its conflicting stickers and [piece] every sticker of its piece(s), both sorted
 * ascending, except the wrong-color-counts problem, whose stickers are the likely misreads in rank order (best first,
 * #174). Both are empty for a problem that names no sticker.
 */
data class ReviewProblem(
    val reason: Text,
    val positions: List<Int>,
    val piece: List<Int>,
)

/** Local checks in home-frame corner, then edge order. Only fully observed pieces are checked. */
object PieceCheck {
    fun problems(stickers: List<Color?>): List<ReviewProblem> =
        buildList {
            val kinds =
                listOf(
                    PieceKind.CORNER to FaceletTables.CORNER_FACELETS,
                    PieceKind.EDGE to FaceletTables.EDGE_FACELETS,
                )
            kinds.forEach { (kind, pieces) ->
                pieces.forEach { positions ->
                    if (positions.all { stickers[it] != null }) {
                        val piece = positions.sorted()
                        Color.entries.forEach { color ->
                            val repeated = positions.filter { stickers[it] == color }.sorted()
                            if (repeated.size > 1) {
                                val key =
                                    when (color) {
                                        Color.WHITE -> "msg.validation.piece-repeats.white"
                                        Color.YELLOW -> "msg.validation.piece-repeats.yellow"
                                        Color.GREEN -> "msg.validation.piece-repeats.green"
                                        Color.BLUE -> "msg.validation.piece-repeats.blue"
                                        Color.RED -> "msg.validation.piece-repeats.red"
                                        Color.ORANGE -> "msg.validation.piece-repeats.orange"
                                    }
                                val reason = Text(key, mapOf("piece" to kind.id, "count" to repeated.size))
                                add(ReviewProblem(reason, repeated, piece))
                            }
                        }
                        listOf(Color.WHITE to Color.YELLOW, Color.GREEN to Color.BLUE, Color.RED to Color.ORANGE).forEach { (a, b) ->
                            if (positions.any { stickers[it] == a } && positions.any { stickers[it] == b }) {
                                val reason = Text("msg.validation.piece-opposite", mapOf("piece" to kind.id))
                                val offending = positions.filter { stickers[it] == a || stickers[it] == b }.sorted()
                                add(ReviewProblem(reason, offending, piece))
                            }
                        }
                    }
                }
            }
        }
}
