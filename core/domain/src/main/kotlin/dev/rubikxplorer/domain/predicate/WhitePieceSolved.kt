package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.FaceletTables
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * One white-layer piece sits correctly (a step of Stages 2–3): [piece] names a white-layer edge or
 * corner by one of 8 canonical names: the edges `white-green`, `white-red`, `white-blue`,
 * `white-orange` and the corners `white-green-red`, `white-red-blue`, `white-blue-orange`,
 * `white-orange-green` (one spelling each, so equal pieces give equal predicates). The piece holds when WHITE is on the face whose center is WHITE
 * and its other stickers match the centers of the faces they sit on, read exactly as
 * [WhiteCrossSolved] and [WhiteCornersSolved] read the layer: color-anchored and orientation-invariant.
 * Progress is 0/1 or 1/1; the preserved piece is [piece] while it is correct.
 */
@Serializable
@SerialName("white-piece-solved")
data class WhitePieceSolved(
    val piece: String,
) : Predicate {
    @Transient
    private val target: Piece =
        requireNotNull(parse(piece)) { "white-piece-solved piece is not a white-layer edge or corner: \"$piece\"" }

    @Transient
    private val slots: List<List<Int>> =
        if (target.colors.size == EDGE_COLORS) FaceletTables.EDGE_FACELETS else FaceletTables.CORNER_FACELETS

    override fun isSatisfied(state: CubeState): Boolean = target in WhiteLayer.correctPieces(state, slots)

    override fun preservedPieces(state: CubeState): Set<Piece> = if (isSatisfied(state)) setOf(target) else emptySet()

    override fun progress(state: CubeState): ObjectiveProgress = ObjectiveProgress(if (isSatisfied(state)) 1 else 0, 1)

    private companion object {
        const val EDGE_COLORS = 2

        /** The 8 white-layer pieces by their one canonical name: 4 edges, then 4 corners in side order. */
        val PIECES: Map<String, Piece> =
            mapOf(
                "white-green" to setOf(Color.GREEN),
                "white-red" to setOf(Color.RED),
                "white-blue" to setOf(Color.BLUE),
                "white-orange" to setOf(Color.ORANGE),
                "white-green-red" to setOf(Color.GREEN, Color.RED),
                "white-red-blue" to setOf(Color.RED, Color.BLUE),
                "white-blue-orange" to setOf(Color.BLUE, Color.ORANGE),
                "white-orange-green" to setOf(Color.ORANGE, Color.GREEN),
            ).mapValues { (_, sides) -> Piece(sides + Color.WHITE) }

        /** The white-layer piece [name] spells, or null unless it is one of the canonical [PIECES] names. */
        fun parse(name: String): Piece? = PIECES[name]
    }
}
