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

        val edges = mutableMapOf<Int, Set<Face>>()
        val corners = mutableMapOf<Int, Set<Face>>()

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
            val tables = if (corner) FaceletTables.CORNER_FACES else FaceletTables.EDGE_FACES
            val complete = colors.size == if (corner) 3 else 2
            val decoded =
                if (complete) {
                    tables.indexOfFirst { faces ->
                        val home = faces.map(Color::home)
                        if (corner) {
                            home.indices.any { offset -> colors.indices.all { colors[it] == home[(it + offset) % home.size] } }
                        } else {
                            home.toSet() == colors.toSet()
                        }
                    }
                } else {
                    -1
                }
            val involved = seen.map { it.first }.toSet()
            if (invalidPair || (complete && decoded < 0)) bad += involved
            if (decoded >= 0) {
                val previous = (if (corner) corners else edges).put(decoded, involved)
                if (previous != null) {
                    bad += previous
                    bad += involved
                }
            }
        }
        FaceletTables.EDGE_FACELETS.forEach { inspect(it, false) }
        FaceletTables.CORNER_FACELETS.forEach { inspect(it, true) }
        return if (bad.isEmpty()) PartialCheck.Consistent else PartialCheck.Inconsistent(bad)
    }
}
