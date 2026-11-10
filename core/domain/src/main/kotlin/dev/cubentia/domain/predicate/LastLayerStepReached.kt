package dev.cubentia.domain.predicate

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceletTables
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.lastlayer.LastLayerReading
import dev.cubentia.domain.lastlayer.LastLayerStep
import dev.cubentia.domain.lastlayer.LastLayerView
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A color-anchored LL goal that also requires solved first two layers. */
@Serializable
@SerialName("last-layer-step")
data class LastLayerStepReached(
    val step: LastLayerStep,
) : Predicate {
    override fun isSatisfied(state: CubeState): Boolean = preservedPieces(state).size == step.pieces

    override fun progress(state: CubeState): ObjectiveProgress = ObjectiveProgress(preservedPieces(state).size, step.pieces)

    override fun preservedPieces(state: CubeState): Set<Piece> {
        val colors = state.toFacelets().colors
        val white = Face.entries.single { colors[FaceletTables.center(it)] == Color.WHITE }
        val lastLayer = colors[FaceletTables.center(white.opposite)]
        return when (val reading = LastLayerView.of(state, lastLayer)) {
            LastLayerReading.FirstTwoLayersUnsolved -> emptySet()
            is LastLayerReading.View -> LastLayerMatcher.stepPieces(reading.view, step).map(::Piece).toSet()
        }
    }
}
