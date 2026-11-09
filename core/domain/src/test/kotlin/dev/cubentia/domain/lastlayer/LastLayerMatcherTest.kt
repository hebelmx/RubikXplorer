package dev.cubentia.domain.lastlayer

import dev.cubentia.domain.content.Algorithm
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.Case
import dev.cubentia.domain.content.CaseId
import dev.cubentia.domain.content.CaseSet
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationError
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.inverse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 9.2: the frame, AUF, views and partial sets of the one [LastLayerMatcher]. */
class LastLayerMatcherTest {
    private val bundle = AlgorithmVerification.shippedBundle()
    private val matcher = (LastLayerMatcher.of(bundle.cases, bundle.algorithms) as LastLayerMatcherResult.Built).matcher
    private val primaries = bundle.algorithms.filter { it.isPrimary }.associateBy { it.caseId }

    private fun moves(text: String): List<Move> = (Notation.parse(text) as NotationResult.Parsed).moves

    private fun inverseOf(case: Case): List<Move> = moves(primaries.getValue(case.id).notation).inverse()

    private fun caseState(case: Case): CubeState = CubeState.SOLVED.apply(inverseOf(case))

    private fun viewOf(
        state: CubeState,
        lastLayer: Color = Color.WHITE,
    ): LastLayerView = (LastLayerView.of(state, lastLayer) as LastLayerReading.View).view

    private fun masked(
        view: LastLayerView,
        seen: (Int) -> Boolean,
    ): LastLayerView =
        LastLayerView.partial(view.lastLayer, view.ring.first(), view.stickers.mapIndexed { i, color -> color.takeIf { seen(i) } })

    private fun turns(
        letter: String,
        count: Int,
    ): List<Move> = List(count) { moves(letter) }.flatten()

    private fun ofSet(set: CaseSet): List<Case> = bundle.cases.filter { it.set == set }

    @Test
    fun `each shipped Case state matches only its own Case`() {
        assertEquals(78, bundle.cases.size)
        bundle.cases.forEach { case -> assertEquals(setOf(case.id), matcher.candidates(viewOf(caseState(case))), case.id.value) }
    }

    @Test
    fun `a Case with no primary Algorithm is Invalid`() {
        val case = bundle.cases.first()
        val others = bundle.algorithms.filter { it.caseId != case.id }
        assertEquals(
            LastLayerMatcherResult.Invalid(listOf(LastLayerProblem.NoPrimaryAlgorithm(case.id))),
            LastLayerMatcher.of(bundle.cases, others),
        )
    }

    @Test
    fun `unparseable Notation is Invalid with the NotationError`() {
        val case = Case(CaseId("case-pll-t"), CaseSet.PLL, bundle.cases.first().family, bundle.cases.first().name)
        val algorithm = Algorithm(AlgorithmId("alg-pll-t-a"), case.id, "R Q")
        assertEquals(
            LastLayerMatcherResult.Invalid(
                listOf(LastLayerProblem.BadAlgorithmNotation(algorithm.id, NotationError.BadToken("Q", 1))),
            ),
            LastLayerMatcher.of(listOf(case), listOf(algorithm)),
        )
    }

    @Test
    fun `an Algorithm that disturbs the first two layers is Invalid`() {
        val case = bundle.cases.first()
        val algorithm = Algorithm(AlgorithmId("alg-x-a"), case.id, "R")
        assertEquals(
            LastLayerMatcherResult.Invalid(listOf(LastLayerProblem.LeavesFirstTwoLayers(algorithm.id))),
            LastLayerMatcher.of(listOf(case), listOf(algorithm)),
        )
    }

    @Test
    fun `an OLL Case keeps its match under every pre-AUF`() {
        ofSet(CaseSet.OLL).forEach { case ->
            (0..3).forEach { k ->
                val state = caseState(case).apply(turns("U", k))
                assertEquals(setOf(case.id), matcher.candidates(viewOf(state)), "${case.id} U^$k")
            }
        }
    }

    @Test
    fun `an OLL Case keeps its match under any LL permutation, AUF and view`() {
        ofSet(CaseSet.OLL).forEach { oll ->
            ofSet(CaseSet.PLL).forEach { pll ->
                (0..3).forEach { k ->
                    val state =
                        CubeState.SOLVED
                            .apply(turns("U", k))
                            .apply(inverseOf(pll))
                            .apply(inverseOf(oll))
                            .apply(turns("U", k))
                            .apply(turns("y", k))
                    assertEquals(setOf(oll.id), matcher.candidates(viewOf(state)), "${oll.id} ${pll.id} $k")
                }
            }
        }
    }

    @Test
    fun `a PLL Case keeps its match under pre-AUF, post-AUF and y rotations`() {
        ofSet(CaseSet.PLL).forEach { case ->
            for (post in 0..3) {
                for (pre in 0..3) {
                    for (view in 0..3) {
                        val state =
                            CubeState.SOLVED
                                .apply(turns("U", post))
                                .apply(inverseOf(case))
                                .apply(turns("U", pre))
                                .apply(turns("y", view))
                        assertEquals(setOf(case.id), matcher.candidates(viewOf(state)), "${case.id} $post $pre $view")
                    }
                }
            }
        }
    }

    @Test
    fun `a y rotation changes the view of a PLL Case`() {
        val case = ofSet(CaseSet.PLL).first()
        assertTrue(viewOf(caseState(case)) != viewOf(caseState(case).apply(moves("y"))))
    }

    @Test
    fun `a Case reached with YELLOW on U reads the same with LL color YELLOW`() {
        bundle.cases.forEach { case ->
            val state = CubeState.SOLVED.apply(moves("z2")).apply(inverseOf(case))
            assertEquals(setOf(case.id), matcher.candidates(viewOf(state, Color.YELLOW)), case.id.value)
        }
    }

    @Test
    fun `a state held in another orientation is re-framed to the LL color`() {
        bundle.cases.forEach { case ->
            val state = caseState(case)
            Orientation.ALL.forEach { orientation ->
                val held = state.copy(orientation = orientation)
                assertEquals(setOf(case.id), matcher.candidates(viewOf(held)), "${case.id} $orientation")
            }
        }
    }

    @Test
    fun `the frame reaches every LL color`() {
        Face.entries.forEach { face ->
            val color = Color.home(face)
            val view = viewOf(CubeState.SOLVED, color)
            assertTrue(matcher.isSolvedUpToAuf(view), color.name)
            assertEquals(color, view.lastLayer)
            assertEquals(4, view.ring.toSet().size)
        }
    }

    @Test
    fun `a scrambled state has unsolved first two layers`() {
        assertEquals(LastLayerReading.FirstTwoLayersUnsolved, LastLayerView.of(CubeState.SOLVED.apply(moves("R U F'")), Color.WHITE))
    }

    @Test
    fun `a state with only second-layer pieces displaced has unsolved first two layers`() {
        val insert = "U R U' R' U' F' U F"
        val state = CubeState.SOLVED.apply(moves(insert))
        assertTrue(
            state
                .toFacelets()
                .colors
                .slice(27..35)
                .all { it == Color.YELLOW },
        )
        assertEquals(LastLayerReading.FirstTwoLayersUnsolved, LastLayerView.of(state, Color.WHITE))

        val case = bundle.cases.first()
        val algorithm = Algorithm(AlgorithmId("alg-x-a"), case.id, insert)
        assertEquals(
            LastLayerMatcherResult.Invalid(listOf(LastLayerProblem.LeavesFirstTwoLayers(algorithm.id))),
            LastLayerMatcher.of(listOf(case), listOf(algorithm)),
        )
    }

    @Test
    fun `solved and AUF-only states have no candidates and are solved up to AUF`() {
        (0..3).forEach { k ->
            val view = viewOf(CubeState.SOLVED.apply(turns("U", k)))
            assertEquals(emptySet<CaseId>(), matcher.candidates(view), "U^$k")
            assertTrue(matcher.isSolvedUpToAuf(view), "U^$k")
        }
    }

    @Test
    fun `a Case state is not solved up to AUF`() {
        bundle.cases.forEach { case -> assertFalse(matcher.isSolvedUpToAuf(viewOf(caseState(case))), case.id.value) }
    }

    @Test
    fun `a view with every sticker unseen is consistent with all 78 Cases`() {
        val view = masked(viewOf(CubeState.SOLVED)) { false }
        assertEquals(bundle.cases.map { it.id }.toSet(), matcher.candidates(view))
    }

    @Test
    fun `a view of only the U stickers returns exactly the Cases consistent with them`() {
        val uOnly = (0 until LastLayerView.STICKERS).filter { it % LastLayerView.BLOCK < 2 }.toSet()

        fun uMask(view: LastLayerView): List<Boolean> = uOnly.sorted().map { view.stickers[it] == view.lastLayer }

        fun rotations(mask: List<Boolean>): Set<List<Boolean>> = (0..3).map { k -> mask.drop(2 * k) + mask.take(2 * k) }.toSet()

        bundle.cases.forEach { truth ->
            val view = viewOf(caseState(truth))
            val expected =
                bundle.cases
                    .filter { uMask(view) in rotations(uMask(viewOf(caseState(it)))) }
                    .map { it.id }
                    .toSet()
            val candidates = matcher.candidates(masked(view) { it in uOnly })
            assertEquals(expected, candidates, truth.id.value)
            assertTrue(truth.id in candidates, truth.id.value)
        }
    }

    @Test
    fun `a partial view with every sticker seen matches only its own Case`() {
        bundle.cases.forEach { case ->
            assertEquals(setOf(case.id), matcher.candidates(masked(viewOf(caseState(case))) { true }), case.id.value)
        }
    }

    @Test
    fun `a partial view of the F and R blocks narrows to a set containing the true PLL Case`() {
        ofSet(CaseSet.PLL).forEach { case ->
            val candidates = matcher.candidates(masked(viewOf(caseState(case))) { it < 10 })
            assertTrue(case.id in candidates, case.id.value)
            assertTrue(candidates.size < bundle.cases.size, case.id.value)
        }
    }

    @Test
    fun `any unseen sticker means not solved up to AUF`() {
        val view = viewOf(CubeState.SOLVED)
        (0 until LastLayerView.STICKERS).forEach { unseen ->
            assertFalse(matcher.isSolvedUpToAuf(masked(view) { it != unseen }), "sticker $unseen")
        }
    }
}
