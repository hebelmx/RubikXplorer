package dev.cubentia.domain.cube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OrientationTest {
    @Test
    fun `there are 24 distinct orientations`() {
        assertEquals(24, Orientation.ALL.size)
        assertEquals(24, Orientation.ALL.toSet().size)
        assertEquals(
            24,
            Orientation.ALL
                .map { o -> Face.entries.map { o.homeFaceAt(it) } }
                .toSet()
                .size,
        )
    }

    @Test
    fun `home shows the home frame`() {
        Face.entries.forEach { assertEquals(it, Orientation.HOME.homeFaceAt(it)) }
        assertEquals(
            listOf(Color.WHITE, Color.RED, Color.GREEN, Color.YELLOW, Color.ORANGE, Color.BLUE),
            Face.entries.map { Orientation.HOME.colorAt(it) },
        )
        assertEquals(Orientation.HOME, Orientation.of(Face.U, Face.F))
    }

    @Test
    fun `every orientation is a rotation that keeps opposite faces opposite`() {
        Orientation.ALL.forEach { o ->
            assertEquals(Face.entries.toSet(), Face.entries.map { o.homeFaceAt(it) }.toSet(), "$o")
            Face.entries.forEach { assertEquals(o.homeFaceAt(it).opposite, o.homeFaceAt(it.opposite), "$o $it") }
            assertEquals(o, Orientation.of(o.homeFaceAt(Face.U), o.homeFaceAt(Face.F)))
        }
    }

    @Test
    fun `right follows from up and front by geometry`() {
        // y: the home R face comes to the front, home B to the right.
        val y = Orientation.of(Face.U, Face.R)
        assertEquals(Face.B, y.homeFaceAt(Face.R))
        assertEquals(Face.F, y.homeFaceAt(Face.L))
        assertEquals(Color.BLUE, y.colorAt(Face.R))
        // x: the home F face comes to the top, home D to the front; R stays.
        val x = Orientation.of(Face.F, Face.D)
        assertEquals(Face.R, x.homeFaceAt(Face.R))
        assertEquals(Face.B, x.homeFaceAt(Face.D))
        // z: the home L face comes to the top, home U to the right.
        val z = Orientation.of(Face.L, Face.F)
        assertEquals(Face.U, z.homeFaceAt(Face.R))
    }

    @Test
    fun `up and front must be adjacent`() {
        assertThrows<IllegalArgumentException> { Orientation.of(Face.U, Face.D) }
        assertThrows<IllegalArgumentException> { Orientation.of(Face.F, Face.F) }
    }
}
