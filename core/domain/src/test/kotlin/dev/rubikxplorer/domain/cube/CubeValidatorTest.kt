package dev.rubikxplorer.domain.cube

import dev.rubikxplorer.domain.cube.ValidationResult.FlippedEdge
import dev.rubikxplorer.domain.cube.ValidationResult.ImpossiblePiece
import dev.rubikxplorer.domain.cube.ValidationResult.ParityViolation
import dev.rubikxplorer.domain.cube.ValidationResult.TwistedCorner
import dev.rubikxplorer.domain.cube.ValidationResult.WrongColorCounts
import dev.rubikxplorer.domain.text.Text
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CubeValidatorTest {
    private val solved: List<Color> = CubeState.SOLVED.toFacelets().colors

    private fun solvedIn(o: Orientation): List<Color> = CubeState(Cubies.SOLVED, o).toFacelets().colors

    private fun swap(
        colors: List<Color>,
        a: Int,
        b: Int,
    ): List<Color> =
        colors.toMutableList().also {
            it[a] = colors[b]
            it[b] = colors[a]
        }

    /** Sticker at `from[k]` moves to `from[k + 1]`, the last wrapping to the first. */
    private fun cycle(
        colors: List<Color>,
        vararg from: Int,
    ): List<Color> =
        colors.toMutableList().also { out ->
            from.indices.forEach { k -> out[from[(k + 1) % from.size]] = colors[from[k]] }
        }

    private fun validate(colors: List<Color>) = CubeValidator.validate(Facelets(colors))

    /** Each I/O-matrix failure case, as a transform of solved stickers in any orientation. */
    private val cases: Map<String, (List<Color>) -> List<Color>> =
        mapOf(
            "bad centers" to { c -> swap(c, 4, 31) },
            "two whites" to { c -> swap(c, 8, 18) },
            "mirrored corner" to { c -> swap(c, 9, 20) },
            "twisted corner" to { c -> cycle(c, 8, 9, 20) },
            "flipped edge" to { c -> swap(c, 5, 10) },
            "parity" to { c -> swap(swap(c, 5, 7), 10, 19) },
            "impossible edge" to { c -> swap(c, 10, 7) },
        )

    private fun expected(case: String): ValidationResult =
        when (case) {
            "bad centers" -> ImpossiblePiece(listOf(4, 13, 22, 31, 40, 49), PieceKind.CENTER)
            "two whites" -> ImpossiblePiece(listOf(6, 8, 9, 18, 20, 38), PieceKind.CORNER)
            "mirrored corner" -> ImpossiblePiece(listOf(8, 9, 20), PieceKind.CORNER)
            "twisted corner" -> TwistedCorner
            "flipped edge" -> FlippedEdge
            "parity" -> ParityViolation
            "impossible edge" -> ImpossiblePiece(listOf(5, 7, 10, 12, 19, 23), PieceKind.EDGE)
            else -> error(case)
        }

    @Test
    fun `wrong color counts reports all six colors and no positions`() {
        val result = validate(solved.toMutableList().also { it[0] = Color.YELLOW })
        val expected =
            mapOf(
                Color.WHITE to 8,
                Color.YELLOW to 10,
                Color.GREEN to 9,
                Color.BLUE to 9,
                Color.RED to 9,
                Color.ORANGE to 9,
            )
        assertEquals(WrongColorCounts(expected), result)
        result as WrongColorCounts
        assertEquals(Text("msg.validation.wrong-color-counts"), result.reason)
        assertEquals(emptyList<Int>(), result.positions)
    }

    @Test
    fun `wrong color counts include zero counts`() {
        val result = validate(List(54) { if (it < 27) Color.WHITE else Color.YELLOW }) as WrongColorCounts
        assertEquals(6, result.counts.size)
        assertEquals(0, result.counts.getValue(Color.GREEN))
        assertEquals(27, result.counts.getValue(Color.WHITE))
    }

    @Test
    fun `each failure case from home returns its exact variant`() {
        cases.forEach { (name, transform) -> assertEquals(expected(name), validate(transform(solved)), name) }
    }

    @Test
    fun `each failure case in every other orientation returns the same variant`() {
        Orientation.ALL.filter { it != Orientation.HOME }.forEach { o ->
            cases.forEach { (name, transform) -> assertEquals(expected(name), validate(transform(solvedIn(o))), "$o $name") }
        }
    }

    @Test
    fun `centers that form no orientation are an impossible center`() {
        // U and F centers opposite each other.
        val opposite = swap(solved, 22, 31)
        assertEquals(ImpossiblePiece(listOf(4, 13, 22, 31, 40, 49), PieceKind.CENTER), validate(opposite))
        // U and F adjacent but R and L swapped (a mirror image).
        // U and F centers the same color.
        val same = swap(solved, 22, 0)
        assertEquals(ImpossiblePiece(listOf(4, 13, 22, 31, 40, 49), PieceKind.CENTER), validate(same))
        val mirrored = swap(solved, 13, 40)
        assertEquals(ImpossiblePiece(listOf(4, 13, 22, 31, 40, 49), PieceKind.CENTER), validate(mirrored))
    }

    @Test
    fun `a corner shown twice is impossible at both slots`() {
        // URF (U8 R0 F2 = 8 9 20) is recolored W G O, a second UFL; DLF's L8 (44) takes the spare RED
        // so counts stay 9 each and DLF shows Y R G, the mirror of DFR.
        val c = solved.toMutableList()
        c[9] = Color.GREEN
        c[20] = Color.ORANGE
        c[44] = Color.RED
        val result = validate(c) as ImpossiblePiece
        assertEquals(PieceKind.CORNER, result.piece)
        // URF and UFL both show UFL (duplicate); DLF shows Y R G, no real corner.
        assertEquals((listOf(8, 9, 20) + listOf(6, 18, 38) + listOf(27, 44, 24)).sorted(), result.positions)
    }

    @Test
    fun `an impossible edge is reported with its stickers`() {
        // UR shows W W (no real edge); UF shows R G, a flipped FR, so FR (23, 12) is shown twice.
        val result = validate(swap(solved, 10, 7))
        assertEquals(ImpossiblePiece(listOf(5, 7, 10, 12, 19, 23), PieceKind.EDGE), result)
        result as ImpossiblePiece
        assertEquals(Text("msg.validation.impossible-piece", mapOf("piece" to "edge")), result.reason)
    }

    @Test
    fun `reasons carry their text keys and global rules have no positions`() {
        assertEquals(
            Text("msg.validation.impossible-piece", mapOf("piece" to "center")),
            ImpossiblePiece(listOf(4), PieceKind.CENTER).reason,
        )
        assertEquals(
            Text("msg.validation.impossible-piece", mapOf("piece" to "corner")),
            ImpossiblePiece(listOf(8), PieceKind.CORNER).reason,
        )
        assertEquals(Text("msg.validation.twisted-corner"), TwistedCorner.reason)
        assertEquals(Text("msg.validation.flipped-edge"), FlippedEdge.reason)
        assertEquals(Text("msg.validation.parity"), ParityViolation.reason)
        listOf(TwistedCorner, FlippedEdge, ParityViolation).forEach { assertTrue(it.positions.isEmpty(), "$it") }
    }

    @Test
    fun `every two-sticker swap of different colors in solved is never valid`() {
        val nonCenters = (0 until 54).filter { it % 9 != 4 }
        var cases = 0
        for (i in nonCenters.indices) {
            for (j in i + 1 until nonCenters.size) {
                val a = nonCenters[i]
                val b = nonCenters[j]
                if (solved[a] == solved[b]) continue
                cases++
                assertFalse(validate(swap(solved, a, b)) is ValidationResult.Valid, "swap $a $b")
            }
        }
        assertEquals(960, cases)
    }

    @OptIn(ExperimentalKotest::class)
    private fun config(seed: Long) = PropTestConfig(seed = seed, iterations = 1000)

    /** Never throws, and any Valid result reproduces its input exactly. */
    private fun assertSound(colors: List<Color>) {
        val result = validate(colors)
        if (result is ValidationResult.Valid) assertEquals(colors, result.state.toFacelets().colors, "$colors")
    }

    @Test
    fun `random shuffles of the solved non-center stickers never throw and any Valid round trips`() =
        runBlocking<Unit> {
            val nonCenters = (0 until 54).filter { it % 9 != 4 }
            checkAll(config(seed = 6_404L), Arb.long()) { seed ->
                val shuffled = nonCenters.map { solved[it] }.shuffled(Random(seed))
                val colors = solved.toMutableList()
                nonCenters.forEachIndexed { k, i -> colors[i] = shuffled[k] }
                assertSound(colors)
            }
        }

    @Test
    fun `arbitrary 54-color lists never throw and any Valid round trips`() =
        runBlocking<Unit> {
            checkAll(config(seed = 7_404L), Arb.list(Arb.enum<Color>(), 54..54)) { colors -> assertSound(colors) }
        }
}
