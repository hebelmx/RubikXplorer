package dev.rubikxplorer.domain.recognition

import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class PhysicalObservationTest {
    @Test
    fun fullObservationHoldsValidatedState() {
        val state = CubeState.SOLVED
        val obs = PhysicalObservation.Full(state)
        assertEquals(state, obs.state)
        assertEquals(PhysicalObservation.Full(state), obs)
    }

    @Test
    fun partialObservationHoldsFacesAndOrientationHint() {
        val faces = mapOf(Face.U to List(9) { Color.WHITE })
        val obs1 = PhysicalObservation.Partial(faces, "up:U,front:F")
        val obs2 = PhysicalObservation.Partial(faces, "up:U,front:F")
        val obs3 = PhysicalObservation.Partial(faces, null)

        assertEquals(faces, obs1.faces)
        assertEquals("up:U,front:F", obs1.orientationHint)
        assertEquals(obs1, obs2)
        assertNotEquals(obs1, obs3)
    }
}
