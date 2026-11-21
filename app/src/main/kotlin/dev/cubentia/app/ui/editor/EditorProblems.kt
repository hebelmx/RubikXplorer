package dev.cubentia.app.ui.editor

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.PieceCheck
import dev.cubentia.domain.cube.PieceKind
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.text.Text

/** One problem line of the Manual Cube Editor (#253): specific counts, a named piece, or a global rule. */
sealed interface EditorProblem {
    /** The colors that are off, in palette order (W Y R O G B), each with its count; marks no sticker. */
    data class Counts(
        val off: List<Pair<Color, Int>>,
    ) : EditorProblem

    /**
     * A piece named by its [faces] (URFDLB indices, U/D first, then F/B, then R/L): [key] is its content key, [count]
     * the repeated stickers for a repeat line, [positions] its conflicting stickers and [piece] all its stickers.
     */
    data class Piece(
        val key: String,
        val kind: PieceKind,
        val faces: List<Int>,
        val count: Int,
        val positions: List<Int>,
        val piece: List<Int>,
    ) : EditorProblem

    /** A rule that does not belong to one piece (twist, flip, parity), with its existing line. */
    data class Global(
        val reason: Text,
    ) : EditorProblem
}

/** Builds the editor's problem lines from the validator's failures and the local piece checks. */
internal object EditorProblems {
    private const val PER_FACE = 9

    /** Stickers of each color on a real cube. */
    private const val PER_COLOR = 9
    private const val CORNER_STICKERS = 3

    /** Palette order, matching the glyph order `W Y R O G B`. */
    val PALETTE = listOf(Color.WHITE, Color.YELLOW, Color.RED, Color.ORANGE, Color.GREEN, Color.BLUE)

    /** Sort rank of each face (URFDLB index) in a piece name: U/D, then F/B, then R/L. */
    private val FACE_RANK = listOf(0, 2, 1, 0, 2, 1)

    fun facesOf(stickers: List<Int>): List<Int> = stickers.map { it / PER_FACE }.distinct().sortedWith(compareBy({ FACE_RANK[it] }, { it }))

    /**
     * In order: one counts line when color counts are off; each [PieceCheck] problem as a named piece; when counts are
     * right, each impossible piece not already named; then the global rules.
     */
    fun of(
        facelets: Facelets,
        problems: List<ValidationResult.Invalid>,
    ): List<EditorProblem> =
        buildList {
            val counts = problems.filterIsInstance<ValidationResult.WrongColorCounts>().firstOrNull()
            if (counts != null) {
                add(EditorProblem.Counts(PALETTE.filter { counts.counts[it] != PER_COLOR }.map { it to (counts.counts[it] ?: 0) }))
            }
            val named = mutableSetOf<List<Int>>()
            PieceCheck.problems(facelets.colors).forEach { problem ->
                named += problem.piece
                add(
                    EditorProblem.Piece(
                        key = problem.reason.key.replace("msg.validation.", "msg.editor."),
                        kind = if (problem.piece.size == CORNER_STICKERS) PieceKind.CORNER else PieceKind.EDGE,
                        faces = facesOf(problem.piece),
                        count = problem.reason.args["count"] as? Int ?: 0,
                        positions = problem.positions,
                        piece = problem.piece,
                    ),
                )
            }
            problems.forEach { invalid ->
                when {
                    invalid is ValidationResult.ImpossiblePiece && invalid.piece != PieceKind.CENTER -> {
                        val pieces = PieceCheck.pieces(invalid.piece)
                        pieces
                            .map { it.sorted() }
                            .filter { piece -> piece.all { it in invalid.positions } && piece !in named }
                            .forEach { piece ->
                                add(EditorProblem.Piece("msg.editor.piece-impossible", invalid.piece, facesOf(piece), 0, piece, piece))
                            }
                    }

                    invalid is ValidationResult.WrongColorCounts -> {
                        // Already shown as the counts line.
                    }

                    else -> {
                        add(EditorProblem.Global(invalid.reason))
                    }
                }
            }
        }
}
