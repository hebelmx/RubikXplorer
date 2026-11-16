package dev.cubentia.domain.cube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PieceCheckTest {
    @Test fun `every repeated color on corners and edges outlines only repeats`() {
        listOf(FaceletTables.CORNER_FACELETS to "corner", FaceletTables.EDGE_FACELETS to "edge").forEach { (pieces, kind) ->
            pieces.forEach { piece ->
                Color.entries.forEach { color ->
                    val stickers = MutableList<Color?>(54) { null }.also { values -> piece.forEach { values[it] = color } }
                    val problem = PieceCheck.problems(stickers).single()
                    assertEquals("msg.validation.piece-repeats.${color.name.lowercase()}", problem.reason.key)
                    assertEquals(mapOf("piece" to kind), problem.reason.args)
                    assertEquals(piece.sorted(), problem.positions)
                }
            }
        }
    }

    @Test fun `all opposite pairs on every piece outline only the pair`() {
        (FaceletTables.CORNER_FACELETS + FaceletTables.EDGE_FACELETS).forEach { piece ->
            listOf(Color.WHITE to Color.YELLOW, Color.GREEN to Color.BLUE, Color.RED to Color.ORANGE).forEach { (a, b) ->
                val stickers = MutableList<Color?>(54) { null }
                piece.forEach { stickers[it] = Color.entries.first { c -> c != a && c != b } }
                stickers[piece[0]] = a
                stickers[piece[1]] = b
                val problem = PieceCheck.problems(stickers).single()
                assertEquals("msg.validation.piece-opposite", problem.reason.key)
                assertEquals(piece.take(2).sorted(), problem.positions)
                stickers[piece[0]] = null
                assertTrue(PieceCheck.problems(stickers).isEmpty())
            }
        }
    }

    @Test fun `corners precede edges in table order and repeats precede opposite pairs`() {
        val stickers = MutableList<Color?>(54) { null }
        val corner = FaceletTables.CORNER_FACELETS.first()
        corner.forEach { stickers[it] = Color.WHITE }
        stickers[corner.last()] = Color.YELLOW
        FaceletTables.EDGE_FACELETS.first().forEach { stickers[it] = Color.BLUE }
        val problems = PieceCheck.problems(stickers)
        assertEquals(
            listOf(
                "msg.validation.piece-repeats.white",
                "msg.validation.piece-opposite",
                "msg.validation.piece-repeats.blue",
            ),
            problems.map {
                it.reason.key
            },
        )
    }
}
