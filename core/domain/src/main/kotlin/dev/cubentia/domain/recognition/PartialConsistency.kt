package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceletTables

/** Internal consistency of only the stickers actually observed. */
sealed interface PartialCheck {
    data object Consistent : PartialCheck

    data class Inconsistent(
        val faces: Set<Face>,
    ) : PartialCheck
}

/** Never completes an observation or consults a predicted cube. */
object PartialConsistency {
    fun check(partial: PhysicalObservation.Partial): PartialCheck {
        val bad = mutableSetOf<Face>()
        partial.faces.forEach { (face, colors) ->
            if (colors.size != 9 || colors.getOrNull(4) != Color.home(face)) bad += face
        }
        Color.entries.forEach { color ->
            if (partial.faces.values.sumOf { colors -> colors.count { it == color } } > 9) {
                bad += partial.faces.filterValues { color in it }.keys
            }
        }

        fun inspect(
            indices: List<Int>,
            corner: Boolean,
        ) {
            val seen =
                indices.mapNotNull { index ->
                    val face = Face.entries[index / 9]
                    partial.faces[face]?.getOrNull(index % 9)?.let { face to it }
                }
            val colors = seen.map { it.second }
            val invalidPair =
                colors.indices.any { i ->
                    (i + 1 until colors.size).any { j ->
                        colors[i] == colors[j] || Face.entries.any { Color.home(it) == colors[i] && Color.home(it.opposite) == colors[j] }
                    }
                }
            val invalidCorner =
                corner && colors.size == 3 && FaceletTables.CORNER_FACES.none { faces -> faces.map(Color::home).toSet() == colors.toSet() }
            if (invalidPair || invalidCorner) bad += seen.map { it.first }
        }
        FaceletTables.EDGE_FACELETS.forEach { inspect(it, false) }
        FaceletTables.CORNER_FACELETS.forEach { inspect(it, true) }
        return if (bad.isEmpty()) PartialCheck.Consistent else PartialCheck.Inconsistent(bad)
    }
}
