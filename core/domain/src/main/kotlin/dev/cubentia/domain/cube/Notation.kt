package dev.cubentia.domain.cube

/**
 * The only conversion between strings and [Move]s. Canonical tokens: face `R R' R2`, wide `r r' r2`,
 * slice `M M' M2`, Cube Rotation `x x' x2`. [parse] also accepts `2'` as a half turn (`R2' == R2`).
 */
object Notation {
    private val TOKEN = Regex("([URFDLBurfdlbMESxyz])(|'|2|2')")

    /** The Moves in [text], tokens separated by whitespace; blank text is no Moves. Never throws. */
    fun parse(text: String): NotationResult {
        val moves = mutableListOf<Move>()
        tokens(text).forEachIndexed { index, token ->
            val match = TOKEN.matchEntire(token) ?: return NotationResult.Invalid(NotationError.BadToken(token, index))
            moves += move(match.groupValues[1].single(), turn(match.groupValues[2]))
        }
        return NotationResult.Parsed(moves)
    }

    // Splits on Char.isWhitespace, the same definition trim() uses, so Unicode spaces separate tokens.
    private fun tokens(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (char in text) {
            if (char.isWhitespace()) {
                if (current.isNotEmpty()) result += current.toString().also { current.clear() }
            } else {
                current.append(char)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    /** The canonical token for [move]. */
    fun format(move: Move): String =
        when (move) {
            is FaceTurn -> move.face.name + suffix(move.turn)
            is WideTurn -> move.face.name.lowercase() + suffix(move.turn)
            is SliceTurn -> move.slice.name + suffix(move.turn)
            is CubeRotation -> move.axis.name.lowercase() + suffix(move.turn)
        }

    /** The canonical tokens for [moves], joined by one space; no Moves is `""`. */
    fun format(moves: List<Move>): String = moves.joinToString(" ") { format(it) }

    private fun move(
        letter: Char,
        turn: Turn,
    ): Move =
        when (letter) {
            in "URFDLB" -> FaceTurn(Face.valueOf(letter.toString()), turn)
            in "urfdlb" -> WideTurn(Face.valueOf(letter.uppercase()), turn)
            in "MES" -> SliceTurn(Slice.valueOf(letter.toString()), turn)
            else -> CubeRotation(Axis.valueOf(letter.uppercase()), turn)
        }

    private fun turn(suffix: String): Turn =
        when (suffix) {
            "" -> Turn.CLOCKWISE
            "'" -> Turn.COUNTERCLOCKWISE
            else -> Turn.HALF
        }

    private fun suffix(turn: Turn): String =
        when (turn) {
            Turn.CLOCKWISE -> ""
            Turn.HALF -> "2"
            Turn.COUNTERCLOCKWISE -> "'"
        }
}

/** The outcome of [Notation.parse]. */
sealed interface NotationResult {
    /** Every token was valid; [moves] in input order. */
    data class Parsed(
        val moves: List<Move>,
    ) : NotationResult

    /** A token was not valid notation. */
    data class Invalid(
        val error: NotationError,
    ) : NotationResult
}

/** Why text is not valid notation. */
sealed interface NotationError {
    /** The first bad [token], exactly as written, at 0-based token [index]. */
    data class BadToken(
        val token: String,
        val index: Int,
    ) : NotationError
}
