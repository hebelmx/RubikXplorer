package dev.cubentia.domain.cube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SliceFixtureTest {
    private val letters =
        mapOf(
            'W' to Color.WHITE,
            'Y' to Color.YELLOW,
            'G' to Color.GREEN,
            'B' to Color.BLUE,
            'R' to Color.RED,
            'O' to Color.ORANGE,
        )

    private val fixtures: Map<String, List<Color>> =
        checkNotNull(javaClass.getResource("/fixtures/slice-moves.txt")) { "missing fixture" }
            .readText()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val (token, stickers) = line.split(Regex("\\s+"))
                check(stickers.length == 54) { "$token has ${stickers.length} stickers" }
                token to stickers.map { letters.getValue(it) }
            }

    private fun stateAfter(token: String): CubeState =
        if (token == "SOLVED") {
            CubeState.SOLVED
        } else {
            CubeState.SOLVED.apply((Notation.parse(token) as NotationResult.Parsed).moves)
        }

    private fun after(token: String): List<Color> = StickerReader.read(stateAfter(token))

    private fun idx(
        face: Face,
        r: Int,
        c: Int,
    ) = face.ordinal * 9 + r * 3 + c

    private fun row(
        face: Face,
        r: Int,
    ) = List(3) { idx(face, r, it) }

    private fun col(
        face: Face,
        c: Int,
    ) = List(3) { idx(face, it, c) }

    @Test
    fun `fixture covers solved, the outer layers and every slice`() {
        assertEquals(listOf("SOLVED", "L", "D", "F", "M", "M'", "E", "E'", "S", "S'"), fixtures.keys.toList())
    }

    @Test
    fun `solved and each Move from solved match the fixture`() {
        fixtures.forEach { (token, expected) -> assertEquals(expected, after(token), token) }
    }

    @Test
    fun `toFacelets matches the hand-derived fixture`() {
        fixtures.forEach { (token, expected) -> assertEquals(expected, stateAfter(token).toFacelets().colors, token) }
    }

    @Test
    fun `solved in any orientation shows each Learner face in its center color`() {
        Orientation.ALL.forEach { o ->
            val stickers = StickerReader.read(CubeState(Cubies.SOLVED, o))
            Face.entries.forEach { f ->
                assertEquals(List(9) { o.colorAt(f) }, stickers.subList(f.ordinal * 9, f.ordinal * 9 + 9), "$o $f")
            }
        }
    }

    @Test
    fun `a slice after a rotation acts through orientation`() {
        val expected = "WOWWOWWOW" + "BBBBBBBBB" + "RWRRWRRWR" + "YRYYRYYRY" + "GGGGGGGGG" + "OYOOYOOYO"
        assertEquals(expected.map { letters.getValue(it) }, after("y M"))
    }

    /** Pairs of (outer cells changed by the face turn, matching middle cells changed by the slice). */
    private val adjacent: Map<String, List<Pair<List<Int>, List<Int>>>> =
        mapOf(
            "M" to
                listOf(
                    col(Face.U, 0) to col(Face.U, 1),
                    col(Face.F, 0) to col(Face.F, 1),
                    col(Face.D, 0) to col(Face.D, 1),
                    col(Face.B, 2) to col(Face.B, 1),
                ),
            "E" to listOf(Face.F, Face.R, Face.B, Face.L).map { row(it, 2) to row(it, 1) },
            "S" to
                listOf(
                    row(Face.U, 2) to row(Face.U, 1),
                    col(Face.R, 0) to col(Face.R, 1),
                    row(Face.D, 0) to row(Face.D, 1),
                    col(Face.L, 2) to col(Face.L, 1),
                ),
        )

    private val outerFace = mapOf("M" to "L", "E" to "D", "S" to "F")

    @Test
    fun `each slice moves its middle layer the way L, D and F move their outer layer`() {
        val solved = after("SOLVED")
        adjacent.forEach { (slice, pairs) ->
            listOf("", "'").forEach { suffix ->
                val outer = after(outerFace.getValue(slice) + suffix)
                val middle = after(slice + suffix)
                pairs.forEach { (outerCells, middleCells) ->
                    assertEquals(outerCells.map { outer[it] }, middleCells.map { middle[it] }, "$slice$suffix")
                    middleCells.forEach { assertNotEquals(solved[it], middle[it], "$slice$suffix cell $it") }
                }
                val moved = pairs.flatMap { it.second }.toSet()
                (0 until 54).filter { it !in moved }.forEach { assertEquals(solved[it], middle[it], "$slice$suffix cell $it unchanged") }
            }
        }
    }
}
