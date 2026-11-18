package dev.cubentia.application.usecase

import dev.cubentia.domain.content.CaseId
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.lastlayer.CaseStateResult
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.recognition.CornerView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import kotlin.time.measureTime

class RecognizeCaseTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val recognize = checkNotNull(RecognizeCase.of(bundle.cases, bundle.algorithms))

    @Test
    fun allYellowCasesMatchFromAnOppositePairAndFromTheManualEditor() {
        bundle.algorithms.filter { it.isPrimary }.forEach { algorithm ->
            val state = (LastLayerMatcher.caseState(algorithm, Color.YELLOW) as CaseStateResult.Derived).state
            val views = corners(state)
            val expected = Recognition.Unique(algorithm.caseId)
            val elapsed = measureTime { assertEquals(expected, recognize.fromCorners(views), algorithm.id.value) }
            assertTrue(elapsed.inWholeMilliseconds < 1_000)
            assertEquals(expected, recognize.fromCube(state))
        }
    }

    @Test
    fun extraViewIntersectsTheEarlierCandidatesAndCanReturnNoFit() {
        val id = CaseId("case-oll-45")
        val algorithm = bundle.algorithms.single { it.caseId == id && it.isPrimary }
        val state = (LastLayerMatcher.caseState(algorithm, Color.YELLOW) as CaseStateResult.Derived).state
        val views = corners(state)
        val masked =
            views.map {
                it.copy(top = mask(it.top), left = mask(it.left), right = mask(it.right))
            }
        val several = assertInstanceOf<Recognition.Several>(recognize.fromCorners(masked))
        assertTrue(id in several.caseIds)
        val additional = assertInstanceOf<Recognition.Several>(recognize.fromCorners(masked.take(1), several.caseIds))
        assertEquals(several, additional)
        val narrowed = recognize.fromCorners(views.take(1), several.caseIds)
        val narrowedIds =
            when (narrowed) {
                is Recognition.Unique -> setOf(narrowed.caseId)
                is Recognition.Several -> narrowed.caseIds
                else -> error("Expected the captured case to remain a candidate: $narrowed")
            }
        assertTrue(id in narrowedIds && narrowedIds.size < several.caseIds.size)
        assertEquals(Recognition.Unique(id), recognize.fromCorners(views.take(1), setOf(id)))
        assertEquals(Recognition.NoFit, recognize.fromCorners(views.take(1), emptySet()))
        assertEquals(Recognition.NoFit, recognize.fromCorners(listOf(views.first(), views.first())))
    }

    @Test
    fun solvedUpToAufAndUnsolvedFirstTwoLayersAreRecognizedBeforeMatching() {
        val yellowUp = CubeState.SOLVED.reoriented(Orientation.of(Face.D, Face.F))
        val auf = yellowUp.apply((Notation.parse("U") as NotationResult.Parsed).moves)
        assertEquals(Recognition.LastLayerSolved, recognize.fromCorners(corners(auf), emptySet()))
        assertEquals(Recognition.LastLayerSolved, recognize.fromCube(auf))

        val disturbed = yellowUp.apply((Notation.parse("F") as NotationResult.Parsed).moves)
        assertEquals(Recognition.NotAtLastLayer, recognize.fromCorners(corners(disturbed)))
        assertEquals(Recognition.NotAtLastLayer, recognize.fromCube(disturbed))
        assertNull(RecognizeCase.of(bundle.cases, emptyList()))
    }

    @Test
    fun aStickerTheCornersReadDifferentlyNeverBringsBackACaseOneCornerRulesOut() {
        val algorithm = bundle.algorithms.single { it.id.value == "alg-oll-45-a" }
        val state = (LastLayerMatcher.caseState(algorithm, Color.YELLOW) as CaseStateResult.Derived).state
        val (first, opposite) = corners(state)
        // The opposite corner's U-F edge is the first corner's U-B edge: read it the other way.
        val flipped = if (opposite.top[7] == Color.YELLOW) Color.GREEN else Color.YELLOW
        val conflicted = opposite.copy(top = opposite.top.toMutableList().apply { this[7] = flipped })
        val pair = ids(recognize.fromCorners(listOf(first, conflicted)))
        assertTrue(pair.all { it in ids(recognize.fromCorners(listOf(first))) })
        assertTrue(pair.all { it in ids(recognize.fromCorners(listOf(conflicted))) })
    }

    private fun ids(recognition: Recognition): Set<CaseId> =
        when (recognition) {
            is Recognition.Unique -> setOf(recognition.caseId)
            is Recognition.Several -> recognition.caseIds
            else -> emptySet()
        }

    private fun mask(face: List<Color?>): List<Color?> = face.mapIndexed { index, color -> color.takeIf { index == 4 } }

    private fun corners(state: CubeState): List<CornerView> =
        listOf(Face.F, Face.B).map { front ->
            val stickers =
                state
                    .reoriented(Orientation.of(Face.D, front))
                    .toFacelets()
                    .colors
                    .chunked(9)
            CornerView(stickers[Face.U.ordinal], stickers[Face.F.ordinal], stickers[Face.R.ordinal])
        }
}
