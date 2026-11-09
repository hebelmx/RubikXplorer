package dev.cubentia.application.usecase

import dev.cubentia.application.port.RandomSource
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.ValidationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Modifier
import kotlin.math.abs

class NewScrambleTest {
    private fun faceTurns(moves: List<Move>): List<FaceTurn> = moves.map { assertInstanceOf(FaceTurn::class.java, it) }

    private val Face.axis: Int get() = minOf(ordinal, opposite.ordinal)

    private fun assertNoCancellation(moves: List<FaceTurn>) {
        moves.zipWithNext().forEach { (a, b) -> assertNotEquals(a.face, b.face, "neighbors share a face in $moves") }
        moves.windowed(3).forEach { (a, b, c) ->
            assertFalse(a.face.axis == b.face.axis && b.face.axis == c.face.axis, "three in a row on one axis in $moves")
        }
    }

    /** Answers each draw from [script] (then 0), recording every `until` it is asked for. */
    private class ScriptedRandomSource(
        private val script: List<Int>,
    ) : RandomSource {
        val untils = mutableListOf<Int>()

        override fun nextInt(until: Int): Int = script.getOrElse(untils.size) { 0 }.also { untils += until }
    }

    private fun validState(moves: List<Move>): CubeState {
        val state = CubeState.SOLVED.apply(moves)
        val result = assertInstanceOf(ValidationResult.Valid::class.java, CubeValidator.validate(state.toFacelets()))
        assertEquals(state, result.state)
        return state
    }

    @Test
    fun `a Scramble is 25 face turns from home that validate to a legal state`() {
        val moves = NewScramble(SeededRandomSource(1))()
        assertEquals(NewScramble.LENGTH, faceTurns(moves).size)
        val state = validState(moves)
        assertEquals(Orientation.HOME, state.orientation)
    }

    @Test
    fun `the same seed reproduces the same Moves`() {
        assertEquals(NewScramble(SeededRandomSource(42))(), NewScramble(SeededRandomSource(42))())
    }

    @Test
    fun `different seeds give different Moves`() {
        assertNotEquals(NewScramble(SeededRandomSource(1))(), NewScramble(SeededRandomSource(2))())
    }

    @Test
    fun `no Move cancels or merges with its neighbors`() {
        val newScramble = NewScramble(SeededRandomSource(7))
        repeat(200) { assertNoCancellation(faceTurns(newScramble())) }
    }

    @Test
    fun `10,000 Scrambles are valid, deep, distinct and evenly spread`() {
        val newScramble = NewScramble(SeededRandomSource(2026))
        val states = HashSet<CubeState>()
        val counts = HashMap<FaceTurn, Int>()
        var oppositeNeighbors = 0
        repeat(10_000) {
            val moves = faceTurns(newScramble())
            assertEquals(NewScramble.LENGTH, moves.size)
            assertNoCancellation(moves)
            val state = validState(moves)
            assertFalse(state.isSolved)
            states += state
            moves.forEach { counts.merge(it, 1, Int::plus) }
            oppositeNeighbors += moves.zipWithNext().count { (a, b) -> b.face == a.face.opposite }
        }
        assertEquals(10_000, states.size)
        assertTrue(oppositeNeighbors > 0, "opposite faces never follow each other")
        val expected = 10_000.0 * NewScramble.LENGTH / 18
        val all = Face.entries.flatMap { face -> Turn.entries.map { FaceTurn(face, it) } }
        assertEquals(18, all.size)
        all.forEach { turn ->
            val count = counts[turn] ?: 0
            assertTrue(abs(count - expected) <= expected * 0.03, "$turn occurred $count times, expected ~$expected")
        }
    }

    @Test
    fun `face draws range over the allowed faces and turn draws over the three turns`() {
        // R (index 1 of U R F D L B), L (index 3 of U F D L B), then whatever the third draw picks.
        for (third in 0 until 4) {
            val source = ScriptedRandomSource(listOf(1, 0, 3, 0, third, 0))
            val moves = faceTurns(NewScramble(source)())
            assertEquals(listOf(Face.R, Face.L), moves.take(2).map { it.face })
            assertFalse(moves[2].face == Face.R || moves[2].face == Face.L, "${moves[2]} follows R L")
            assertEquals(listOf(6, 3, 5, 3, 4, 3), source.untils.take(6))
            assertEquals(2 * NewScramble.LENGTH, source.untils.size)
            assertTrue(source.untils.filterIndexed { i, _ -> i % 2 == 1 }.all { it == 3 })
            assertTrue(
                source.untils
                    .drop(2)
                    .filterIndexed { i, _ -> i % 2 == 0 }
                    .all { it == 4 || it == 5 },
            )
        }
    }

    @Test
    fun `a source returning a value out of range is programmer error`() {
        val tooHigh =
            object : RandomSource {
                override fun nextInt(until: Int): Int = until
            }
        assertThrows<IllegalStateException> { NewScramble(tooHigh)() }
        val negative =
            object : RandomSource {
                override fun nextInt(until: Int): Int = -1
            }
        assertThrows<IllegalStateException> { NewScramble(negative)() }
        var calls = 0
        val badLaterTurn =
            object : RandomSource {
                override fun nextInt(until: Int): Int = if (++calls == 4) until else 0
            }
        assertThrows<IllegalStateException> { NewScramble(badLaterTurn)() }
        assertEquals(4, calls)
    }

    @Test
    fun `the port and use case expose no seed`() {
        val portMethods = RandomSource::class.java.declaredMethods.map { it.name to it.parameterTypes.toList() }
        assertEquals(listOf("nextInt" to listOf(Int::class.javaPrimitiveType)), portMethods)

        val constructors = NewScramble::class.java.constructors.filter { Modifier.isPublic(it.modifiers) }
        assertEquals(1, constructors.size)
        assertEquals(listOf(RandomSource::class.java), constructors.single().parameterTypes.toList())

        val publicMethods = NewScramble::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) }
        assertEquals(listOf("invoke"), publicMethods.map { it.name })
        val instanceFields = NewScramble::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(listOf("random"), instanceFields.map { it.name })
    }
}
