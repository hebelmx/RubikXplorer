package dev.cubentia.application.usecase

import dev.cubentia.domain.content.Algorithm
import dev.cubentia.domain.content.Case
import dev.cubentia.domain.content.CaseId
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.lastlayer.LastLayerMatcherResult
import dev.cubentia.domain.lastlayer.LastLayerReading
import dev.cubentia.domain.lastlayer.LastLayerView
import dev.cubentia.domain.recognition.CornerLastLayer
import dev.cubentia.domain.recognition.CornerView
import dev.cubentia.domain.recognition.ScanAssembler

sealed interface Recognition {
    data class Unique(
        val caseId: CaseId,
    ) : Recognition

    data class Several(
        val caseIds: Set<CaseId>,
    ) : Recognition

    data object NoFit : Recognition

    data object LastLayerSolved : Recognition

    data object NotAtLastLayer : Recognition
}

class RecognizeCase private constructor(
    private val matcher: LastLayerMatcher,
) {
    fun fromCorners(
        views: List<CornerView>,
        prior: Set<CaseId>? = null,
    ): Recognition {
        val assembled =
            when (views.size) {
                1 -> ScanAssembler.lastLayerView(views[0])
                2 -> ScanAssembler.lastLayerView(views[0], views[1])
                else -> return Recognition.NoFit
            }
        if (assembled !is CornerLastLayer.View) return Recognition.NoFit
        if (assembled.lowerRows.any { (seen, center) -> seen != null && seen != center }) return Recognition.NotAtLastLayer
        // Each corner's own candidates are intersected too, so a sticker the two corners read differently (unseen in
        // the merged view) never lets back a Case one of the corners rules out.
        val own =
            if (views.size == 2) {
                views
                    .map { (ScanAssembler.lastLayerView(it) as CornerLastLayer.View).view }
                    .map(matcher::candidates)
                    .reduce { a, b -> a intersect b }
            } else {
                null
            }
        return recognize(assembled.view, listOfNotNull(prior, own).reduceOrNull { a, b -> a intersect b })
    }

    fun fromCube(state: CubeState): Recognition =
        when (val reading = LastLayerView.of(state, Color.YELLOW)) {
            LastLayerReading.FirstTwoLayersUnsolved -> Recognition.NotAtLastLayer
            is LastLayerReading.View -> recognize(reading.view, null)
        }

    private fun recognize(
        view: LastLayerView,
        prior: Set<CaseId>?,
    ): Recognition {
        if (matcher.isSolvedUpToAuf(view)) return Recognition.LastLayerSolved
        val candidates = matcher.candidates(view)
        val fits = prior?.intersect(candidates) ?: candidates
        return when (fits.size) {
            0 -> Recognition.NoFit
            1 -> Recognition.Unique(fits.single())
            else -> Recognition.Several(fits)
        }
    }

    companion object {
        fun of(
            cases: List<Case>,
            algorithms: List<Algorithm>,
        ): RecognizeCase? = (LastLayerMatcher.of(cases, algorithms) as? LastLayerMatcherResult.Built)?.let { RecognizeCase(it.matcher) }
    }
}
