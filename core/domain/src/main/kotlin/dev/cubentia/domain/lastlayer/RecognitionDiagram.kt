package dev.cubentia.domain.lastlayer

import dev.cubentia.domain.content.Algorithm
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.NotationError

/**
 * A Case's recognition diagram (AD-19): the U face with one band of three side stickers per side,
 * laid out on a [SIZE] by [SIZE] grid. [cells] is row-major, with F at the bottom; a null cell has
 * nothing to draw. Always derived from a [LastLayerView], never stored in content.
 */
@ConsistentCopyVisibility
data class RecognitionDiagram private constructor(
    val cells: List<Color?>,
) {
    companion object {
        /** Rows and columns of the grid. */
        const val SIZE: Int = 5

        val LIBRARY_LAST_LAYER: Color = Color.YELLOW

        private const val E = -1
        private const val C = -2

        // Indices into LastLayerView.stickers; E is an empty corner, C is the LL center.
        // R and L read back to front, and B right to left, as each band sits around the U face.
        private val GRID =
            listOf(
                listOf(E, 14, 13, 12, E),
                listOf(17, 16, 10, 11, 9),
                listOf(18, 15, C, 5, 8),
                listOf(19, 1, 0, 6, 7),
                listOf(E, 2, 3, 4, E),
            ).flatten()

        /** The diagram showing [view]; a sticker [view] has not seen stays null. */
        fun of(view: LastLayerView): RecognitionDiagram =
            RecognitionDiagram(
                GRID.map { index ->
                    when (index) {
                        E -> null
                        C -> view.lastLayer
                        else -> view.stickers[index]
                    }
                },
            )

        /** The diagram of [algorithm]'s Case with the [lastLayer] color on U, as the Library shows it. */
        fun derive(
            algorithm: Algorithm,
            lastLayer: Color = LIBRARY_LAST_LAYER,
        ): RecognitionDiagramResult =
            when (val derived = LastLayerMatcher.caseState(algorithm, lastLayer)) {
                is CaseStateResult.BadNotation -> {
                    RecognitionDiagramResult.BadNotation(derived.error)
                }

                is CaseStateResult.Derived -> {
                    when (val reading = LastLayerView.of(derived.state, lastLayer)) {
                        LastLayerReading.FirstTwoLayersUnsolved -> RecognitionDiagramResult.FirstTwoLayersUnsolved
                        is LastLayerReading.View -> RecognitionDiagramResult.Derived(of(reading.view))
                    }
                }
            }
    }
}

/** The outcome of [RecognitionDiagram.derive]. */
sealed interface RecognitionDiagramResult {
    data class Derived(
        val diagram: RecognitionDiagram,
    ) : RecognitionDiagramResult

    /** The Algorithm's Notation does not parse. */
    data class BadNotation(
        val error: NotationError,
    ) : RecognitionDiagramResult

    /** The Algorithm disturbs the first two layers, so it has no last-layer Case to show. */
    data object FirstTwoLayersUnsolved : RecognitionDiagramResult
}
