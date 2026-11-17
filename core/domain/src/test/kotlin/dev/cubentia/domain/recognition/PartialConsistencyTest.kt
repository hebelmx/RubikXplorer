package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceletTables
import dev.cubentia.domain.recognition.PartialCheck
import dev.cubentia.domain.recognition.PartialConsistency
import dev.cubentia.domain.recognition.PhysicalObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PartialConsistencyTest {
    private fun partial(vararg faces: Face) = PhysicalObservation.Partial(faces.associateWith { face -> List(9) { Color.home(face) } })

    private fun invalid(
        p: PhysicalObservation.Partial,
        involved: Set<Face>,
    ) {
        val result = PartialConsistency.check(p)
        assertTrue(result is PartialCheck.Inconsistent)
        assertTrue((result as PartialCheck.Inconsistent).faces.containsAll(involved))
    }

    @Test fun `mirrored corner is inconsistent`() {
        piece(FaceletTables.CORNER_FACELETS[0], listOf(Color.WHITE, Color.GREEN, Color.RED))
    }

    @Test fun `duplicate decoded edges and corners are inconsistent`() {
        for ((indices, faces) in listOf(
            FaceletTables.EDGE_FACELETS to FaceletTables.EDGE_FACES,
            FaceletTables.CORNER_FACELETS to FaceletTables.CORNER_FACES,
        )) {
            val observedFaces = (indices[0] + indices[1]).map { Face.entries[it / 9] }.toSet()
            val map = partial(*observedFaces.toTypedArray()).faces.mapValues { it.value.toMutableList() }
            indices[1].zip(faces[0].map(Color::home)).forEach { (index, color) ->
                map.getValue(Face.entries[index / 9])[index % 9] = color
            }
            // Balance the replaced color on an edge whose D sticker is unobserved.
            map.getValue(Face.R)[7] = Color.home(faces[1].first { it !in faces[0] })
            invalid(PhysicalObservation.Partial(map), observedFaces)
        }
    }

    @Test fun `consistent two face observation`() {
        assertEquals(PartialCheck.Consistent, PartialConsistency.check(partial(Face.U, Face.F)))
    }

    @Test fun `wrong center`() {
        invalid(PhysicalObservation.Partial(mapOf(Face.U to List(9) { Color.GREEN })), setOf(Face.U))
    }

    @Test fun `wrong sticker count never throws`() {
        for (n in listOf(0, 8, 10)) {
            invalid(
                PhysicalObservation.Partial(
                    mapOf(Face.U to List(n) { Color.WHITE }),
                ),
                setOf(Face.U),
            )
        }
    }

    @Test fun `color count exceeds nine`() {
        val p = partial(Face.U, Face.D)
        invalid(
            p.copy(
                faces =
                    p.faces + (
                        Face.D to
                            p.faces
                                .getValue(Face.D)
                                .toMutableList()
                                .also { it[0] = Color.WHITE }
                    ),
            ),
            setOf(Face.U, Face.D),
        )
    }

    private fun piece(
        indices: List<Int>,
        colors: List<Color>,
    ) {
        val faces = indices.map { Face.entries[it / 9] }.toSet()
        val map = partial(*faces.toTypedArray()).faces.mapValues { it.value.toMutableList() }
        indices.zip(colors).forEach { (index, color) -> map.getValue(Face.entries[index / 9])[index % 9] = color }
        invalid(PhysicalObservation.Partial(map), faces)
    }

    @Test fun `equal edge colors`() {
        piece(FaceletTables.EDGE_FACELETS[0], listOf(Color.GREEN, Color.GREEN))
    }

    @Test fun `opposite edge colors`() {
        piece(FaceletTables.EDGE_FACELETS[0], listOf(Color.GREEN, Color.BLUE))
    }

    @Test fun `two seen corner stickers cannot be opposite`() {
        piece(FaceletTables.CORNER_FACELETS[0].take(2), listOf(Color.WHITE, Color.YELLOW))
    }

    @Test fun `three corner colors must form real corner`() {
        piece(FaceletTables.CORNER_FACELETS[0], listOf(Color.WHITE, Color.YELLOW, Color.RED))
    }
}
