package dev.cubentia.domain.cube

import io.kotest.common.ExperimentalKotest
import io.kotest.property.PropTestConfig
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FaceletsTest {
    private val letters =
        mapOf("W" to Color.WHITE, "Y" to Color.YELLOW, "G" to Color.GREEN, "B" to Color.BLUE, "R" to Color.RED, "O" to Color.ORANGE)

    /** Each `[section]` of the layout fixture as a cell → token map in URFDLB index order. */
    private val sections: Map<String, List<String>> by lazy {
        val lines =
            checkNotNull(javaClass.getResource("/fixtures/facelets-layout.txt")) { "missing fixture" }
                .readText()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        val result = mutableMapOf<String, List<String>>()
        var i = 0
        while (i < lines.size) {
            val name = lines[i].removeSurrounding("[", "]")
            val rows = lines.subList(i + 1, i + 10).map { it.split(Regex("\\s+")) }
            result[name] = netToFacelets(rows)
            i += 10
        }
        result
    }

    /** Nine net rows (3 of U, 3 of L F R B, 3 of D) → 54 tokens indexed `face.ordinal*9+row*3+col`. */
    private fun netToFacelets(rows: List<List<String>>): List<String> {
        val cells = mutableMapOf<Int, String>()

        fun put(
            face: Face,
            r: Int,
            tokens: List<String>,
        ) {
            check(tokens.size == 3) { "row of $face has ${tokens.size} cells" }
            tokens.forEachIndexed { c, token -> cells[face.ordinal * 9 + r * 3 + c] = token }
        }
        (0 until 3).forEach { r -> put(Face.U, r, rows[r]) }
        (0 until 3).forEach { r ->
            val middle = rows[3 + r]
            check(middle.size == 12) { "middle row has ${middle.size} cells" }
            listOf(Face.L, Face.F, Face.R, Face.B).forEachIndexed { k, face -> put(face, r, middle.subList(3 * k, 3 * k + 3)) }
        }
        (0 until 3).forEach { r -> put(Face.D, r, rows[6 + r]) }
        return List(Facelets.SIZE) { cells.getValue(it) }
    }

    @OptIn(ExperimentalKotest::class)
    private val config = PropTestConfig(seed = 1_404L, iterations = 1000)

    @Test
    fun `layout fixture indexes every cell as face ordinal times 9 plus row times 3 plus col`() {
        assertEquals(listOf("indices", "solved"), sections.keys.toList())
        assertEquals(List(Facelets.SIZE) { it }, sections.getValue("indices").map { it.toInt() })
    }

    @Test
    fun `solved state matches the layout fixture and validates to itself`() {
        val expected = sections.getValue("solved").map { letters.getValue(it) }
        assertEquals(expected, CubeState.SOLVED.toFacelets().colors)
        assertEquals(expected, StickerReader.read(CubeState.SOLVED))
        val result = CubeValidator.validate(Facelets(expected))
        assertEquals(CubeState.SOLVED, (result as ValidationResult.Valid).state)
    }

    @Test
    fun `wrong size is a programmer error`() {
        assertThrows<IllegalArgumentException> { Facelets(List(53) { Color.WHITE }) }
        assertThrows<IllegalArgumentException> { Facelets(List(55) { Color.WHITE }) }
    }

    @Test
    fun `random legal states round trip through facelets and the validator`() =
        runBlocking<Unit> {
            checkAll(config, arbAnyState) { s ->
                val facelets = s.toFacelets()
                assertEquals(StickerReader.read(s), facelets.colors, "$s")
                val result = CubeValidator.validate(facelets)
                assertEquals(s, (result as ValidationResult.Valid).state, "$s")
                assertEquals(facelets, result.state.toFacelets())
            }
        }

    @Test
    fun `solved in every orientation round trips`() {
        Orientation.ALL.forEach { o ->
            val s = CubeState(Cubies.SOLVED, o)
            assertEquals(StickerReader.read(s), s.toFacelets().colors, "$o")
            assertEquals(s, (CubeValidator.validate(s.toFacelets()) as ValidationResult.Valid).state, "$o")
        }
    }
}
