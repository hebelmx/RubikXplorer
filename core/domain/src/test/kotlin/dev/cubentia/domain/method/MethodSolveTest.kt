package dev.cubentia.domain.method

import dev.cubentia.domain.content.Algorithm
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.CaseId
import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.inverse
import dev.cubentia.domain.predicate.Predicate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Story 15.1 (FR-30, AD-21): the beginner method-shaped solution, one segment per Course Stage.
 * Story 15.2 (FR-30, FR-17, FR-18, AD-19, AD-21): 2-look and full OLL + PLL last layers from Library
 * Algorithms, preferring *Learning* or *Learned* ones.
 */
class MethodSolveTest {
    private val bundle: ContentBundle by lazy { (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle }

    private val order: List<StageId> = MethodSolve.FIRST_TWO_LAYER_STAGES + MethodSolve.LAST_LAYER_STAGES

    private fun completion(id: StageId): Predicate =
        bundle.stages
            .single { it.id == id }
            .lessons
            .last()
            .completion

    private fun turns(text: String): List<FaceTurn> = (Notation.parse(text) as NotationResult.Parsed).moves.map { it as FaceTurn }

    private fun solve(
        state: CubeState,
        shouldContinue: () -> Boolean = { true },
    ): MethodSolveResult = MethodSolve.solve(state, SolveMethod.BEGINNER, bundle, shouldContinue = shouldContinue)

    private fun solved(state: CubeState): List<MethodSegment> =
        assertInstanceOf(MethodSolveResult.Solved::class.java, solve(state)).segments

    private fun stageOf(segment: MethodSegment): StageId = (segment.tag as SegmentTag.OfStage).stageId

    private fun random(seed: Int): CubeState = RandomLegalStates.randomLegalState(Random(seed))

    @Test
    fun `a solved cube in any orientation needs no segment`() {
        Orientation.ALL.forEach { orientation ->
            assertEquals(MethodSolveResult.Solved(emptyList()), solve(CubeState.SOLVED.reoriented(orientation)))
        }
    }

    @Test
    fun `a random cube gets Stage-tagged segments in Course order that solve it`() {
        repeat(20) { seed ->
            val state = random(seed)
            val segments = solved(state)
            assertTrue(segments.size <= 5)
            val tags = segments.map(::stageOf)
            assertEquals(order.filter { it in tags }, tags)
            assertTrue(segments.all { it.moves.isNotEmpty() })
            var current = state
            for (segment in segments) {
                current = current.apply(segment.moves)
                val stage = stageOf(segment)
                order.take(order.indexOf(stage) + 1).forEach { assertTrue(completion(it).isSatisfied(current), "$it after $stage") }
            }
            assertTrue(current.isSolved)
        }
    }

    @Test
    fun `first-two-layer segments have no adjacent same-face turns`() {
        repeat(10) { seed ->
            solved(random(100 + seed))
                .filter { stageOf(it) in MethodSolve.FIRST_TWO_LAYER_STAGES }
                .forEach { segment ->
                    segment.moves.map { it as FaceTurn }.zipWithNext().forEach { (a, b) ->
                        assertFalse(a.face == b.face, "${segment.moves}")
                    }
                }
        }
    }

    @Test
    fun `a solved cross gives no cross segment`() {
        repeat(10) { seed ->
            val state = random(200 + seed)
            val first = solved(state).first()
            assertEquals(MethodSolve.FIRST_TWO_LAYER_STAGES.first(), stageOf(first))
            val crossDone = state.apply(first.moves)
            val segments = solved(crossDone)
            assertEquals(StageId("stage-03-first-layer-corners"), stageOf(segments.first()))
            assertTrue(crossDone.apply(segments.flatMap { it.moves }).isSolved)
        }
    }

    @Test
    fun `the Stage Algorithms are the distinct Lesson steps`() {
        val stages = MethodSolve.LAST_LAYER_STAGES.map { id -> bundle.stages.single { it.id == id } }
        assertEquals(listOf(turns("F L D L' D' F'"), turns("L D L' D L D2 L'")), BeginnerLastLayer.algorithmsOf(stages[0]))
        assertEquals(listOf(turns("L D' R' D L' D' R"), turns("L D' L D L D L D' L' D' L2")), BeginnerLastLayer.algorithmsOf(stages[1]))
    }

    @Test
    fun `only the last layer left gives last-layer segments of Algorithms and last-layer turns`() {
        val stages = MethodSolve.LAST_LAYER_STAGES.map { id -> bundle.stages.single { it.id == id } }
        val algorithms = stages.associate { it.id to BeginnerLastLayer.algorithmsOf(it) }
        var lastLayerSegments = 0
        repeat(20) { seed ->
            val state = random(300 + seed)
            val firstTwoLayers = solved(state).filter { stageOf(it) in MethodSolve.FIRST_TWO_LAYER_STAGES }
            val atLastLayer = state.apply(firstTwoLayers.flatMap { it.moves })
            assertTrue(completion(MethodSolve.FIRST_TWO_LAYER_STAGES.last()).isSatisfied(atLastLayer))
            val segments = solved(atLastLayer)
            assertTrue(segments.all { stageOf(it) in MethodSolve.LAST_LAYER_STAGES })
            segments.forEach { segment ->
                // No two adjacent last-layer turns: Algorithms are stripped of them, so a pre-turn or closing turn stands alone.
                // (Back-to-back Algorithms may meet on another face, e.g. F' F: segments are never merged.)
                val turns = segment.moves.map { it as FaceTurn }
                turns.zipWithNext().forEach { (a, b) ->
                    assertFalse(a.face == BeginnerLastLayer.LL_FACE && b.face == BeginnerLastLayer.LL_FACE, "${segment.moves}")
                }
                assertTrue(splits(turns, algorithms.getValue(stageOf(segment))), "${segment.moves}")
                lastLayerSegments++
            }
            assertTrue(atLastLayer.apply(segments.flatMap { it.moves }).isSolved)
        }
        assertTrue(lastLayerSegments > 20)
    }

    // A last-layer segment: last-layer turns (pre-turns, closing turn) and whole Algorithms, nothing else.
    private fun splits(
        moves: List<FaceTurn>,
        algorithms: List<List<FaceTurn>>,
    ): Boolean {
        if (moves.isEmpty()) return true
        if (moves.first().face == BeginnerLastLayer.LL_FACE && splits(moves.drop(1), algorithms)) return true
        return algorithms.any { algorithm ->
            moves.size >= algorithm.size && moves.subList(0, algorithm.size) == algorithm && splits(moves.drop(algorithm.size), algorithms)
        }
    }

    @Test
    fun `a cube held in another orientation is solved in its held frame`() {
        val yellowUp = Orientation.of(Face.D, Face.F)
        val orientations = listOf(yellowUp) + Orientation.ALL.filterIndexed { index, _ -> index % 5 == 0 }
        orientations.forEachIndexed { index, orientation ->
            val state = random(400 + index).reoriented(orientation)
            val segments = solved(state)
            assertTrue(state.apply(segments.flatMap { it.moves }).isSolved)
            assertEquals(solved(state.reoriented(Orientation.HOME)).map { it.tag }, segments.map { it.tag })
        }
    }

    @Test
    fun `a solve told to stop returns Stopped`() {
        assertEquals(MethodSolveResult.Stopped, solve(random(500)) { false })
        var calls = 0
        assertEquals(
            MethodSolveResult.Stopped,
            solve(random(501)) {
                calls++
                calls < 40
            },
        )
    }

    @Test
    fun `the same input gives the same solution`() {
        val state = random(600)
        assertEquals(solve(state), solve(state))
    }

    @Test
    fun `content without beginner Stages or moves is NotFound`() {
        val state = random(700)
        val noStage = bundle.copy(stages = bundle.stages.filter { it.id != MethodSolve.LAST_LAYER_STAGES.last() })
        assertEquals(MethodSolveResult.NotFound, MethodSolve.solve(state, SolveMethod.BEGINNER, noStage))
        val noMoves = bundle.copy(tutorConfig = bundle.tutorConfig.copy(moveSet = "x y"))
        assertEquals(MethodSolveResult.NotFound, MethodSolve.solve(state, SolveMethod.BEGINNER, noMoves))
        val noAlgorithms =
            bundle.copy(
                stages =
                    bundle.stages.map { stage ->
                        if (stage.id != MethodSolve.LAST_LAYER_STAGES.first()) {
                            stage
                        } else {
                            stage.copy(
                                lessons =
                                    stage.lessons.map { lesson ->
                                        lesson.copy(steps = lesson.steps.map { it.copy(moves = "D") })
                                    },
                            )
                        }
                    },
            )
        assertEquals(MethodSolveResult.NotFound, MethodSolve.solve(state, SolveMethod.BEGINNER, noAlgorithms))
        val noLessons = bundle.copy(stages = bundle.stages.map { if (it.id == order.first()) it.copy(lessons = emptyList()) else it })
        assertEquals(MethodSolveResult.NotFound, MethodSolve.solve(state, SolveMethod.BEGINNER, noLessons))
    }

    // ---- Story 15.2: 2-look and full OLL + PLL ----

    private val faster = listOf(SolveMethod.TWO_LOOK, SolveMethod.FULL_OLL_PLL)

    private val yellowUp: Orientation = Orientation.of(Face.D, Face.F)

    private val z2 = CubeRotation(Axis.Z, Turn.HALF)

    private fun moves(text: String): List<Move> = (Notation.parse(text) as NotationResult.Parsed).moves

    private fun solve(
        state: CubeState,
        method: SolveMethod,
        content: ContentBundle = bundle,
        statuses: Map<AlgorithmId, AlgorithmStatus> = emptyMap(),
        shouldContinue: () -> Boolean = { true },
    ): MethodSolveResult = MethodSolve.solve(state, method, content, statuses, shouldContinue)

    private fun solved(
        state: CubeState,
        method: SolveMethod,
        content: ContentBundle = bundle,
        statuses: Map<AlgorithmId, AlgorithmStatus> = emptyMap(),
    ): List<MethodSegment> = assertInstanceOf(MethodSolveResult.Solved::class.java, solve(state, method, content, statuses)).segments

    /** A home-hold cube with solved first two layers whose last layer the [algorithms], played yellow up in order, solve. */
    private fun lastLayer(vararg algorithms: String): CubeState =
        CubeState.SOLVED
            .reoriented(yellowUp)
            .apply(algorithms.flatMap { moves(it) }.inverse())
            .reoriented(Orientation.HOME)

    private fun caseOf(segment: MethodSegment): String = (segment.tag as SegmentTag.OfCase).caseId.value

    /** The Algorithms of [method]'s set with empty statuses, by id: 2-look Lessons' Algorithms, or every primary. */
    private fun setOf(method: SolveMethod): List<Algorithm> =
        if (method == SolveMethod.TWO_LOOK) {
            val ids =
                bundle.twoLookStages
                    .flatMap { it.lessons }
                    .flatMap { bundle.lessonAlgorithms.getValue(it.id) }
                    .toSet()
            bundle.algorithms.filter { it.id in ids }
        } else {
            bundle.algorithms.filter { it.isPrimary }
        }

    // (rotation?)(U?)(Algorithm with the tag's Case)(U?): rotation only first, closing U only last.
    private fun isLastLayerSegment(
        segment: MethodSegment,
        set: List<Algorithm>,
        first: Boolean,
        last: Boolean,
    ): Boolean {
        val caseId = (segment.tag as? SegmentTag.OfCase)?.caseId ?: return false
        var rest = segment.moves
        if (first && rest.firstOrNull() is CubeRotation) rest = rest.drop(1)
        return set.filter { it.caseId == caseId }.any { algorithm ->
            val body = moves(algorithm.notation)
            listOf(rest, rest.drop(1).takeIf { (rest.firstOrNull() as? FaceTurn)?.face == Face.U }).filterNotNull().any { candidate ->
                candidate == body ||
                    (
                        last && candidate.size == body.size + 1 && candidate.dropLast(1) == body &&
                            (candidate.last() as? FaceTurn)?.face == Face.U
                    )
            }
        }
    }

    @Test
    fun `a solved cube needs no segment in 2-look or full`() {
        faster.forEach { method ->
            Orientation.ALL.forEach { orientation ->
                assertEquals(MethodSolveResult.Solved(emptyList()), solve(CubeState.SOLVED.reoriented(orientation), method))
            }
        }
    }

    @Test
    fun `only the last layer left gives Case segments of set Algorithms that start with z2 and solve it`() {
        val state = lastLayer("R U R' U R U2 R'", "R U R' U' R' F R2 U' R' U' R U R' F'", "U")
        faster.forEach { method ->
            val segments = solved(state, method)
            assertTrue(segments.isNotEmpty())
            assertEquals(z2, segments.first().moves.first(), "$method")
            assertTrue(segments.flatMap { it.moves }.drop(1).none { it is CubeRotation && it.axis != Axis.Y }, "$method")
            segments.forEachIndexed { index, segment ->
                assertTrue(isLastLayerSegment(segment, setOf(method), index == 0, index == segments.lastIndex), "$method: ${segment.moves}")
            }
            assertTrue(MethodSolve.conforms(segments, method, bundle))
            assertTrue(state.apply(segments.flatMap { it.moves }).isSolved, "$method")
        }
    }

    @Test
    fun `a random cube gets beginner first-two-layer segments then Case segments`() {
        repeat(10) { seed ->
            val state = random(800 + seed)
            val beginner = solved(state).filter { stageOf(it) in MethodSolve.FIRST_TWO_LAYER_STAGES }
            faster.forEach { method ->
                val segments = solved(state, method)
                assertEquals(beginner, segments.take(beginner.size), "$method")
                assertTrue(
                    segments.drop(beginner.size).all {
                        it.tag is SegmentTag.OfCase ||
                            it.tag == SegmentTag.OfStage(MethodSolve.lastLayerStageOf(method)!!)
                    },
                )
                assertTrue(MethodSolve.conforms(segments, method, bundle), "$method")
                assertTrue(state.apply(segments.flatMap { it.moves }).isSolved, "$method")
            }
        }
    }

    @Test
    fun `a cube held yellow up needs no rotation`() {
        val state = lastLayer("R U R' U R U2 R'", "R U R' U' R' F R2 U' R' U' R U R' F'").reoriented(yellowUp)
        faster.forEach { method ->
            val segments = solved(state, method)
            assertTrue(segments.isNotEmpty())
            assertFalse(segments.first().moves.first() is CubeRotation, "$method")
            assertTrue(state.apply(segments.flatMap { it.moves }).isSolved, "$method")
        }
    }

    @Test
    fun `other holds get the one rotation that brings yellow up`() {
        // Yellow held on D, L, R, F or B gets z2, z, z', x or x' (z keeps the front); yellow held up gets none.
        val expected =
            mapOf(
                Face.D to CubeRotation(Axis.Z, Turn.HALF),
                Face.L to CubeRotation(Axis.Z, Turn.CLOCKWISE),
                Face.R to CubeRotation(Axis.Z, Turn.COUNTERCLOCKWISE),
                Face.F to CubeRotation(Axis.X, Turn.CLOCKWISE),
                Face.B to CubeRotation(Axis.X, Turn.COUNTERCLOCKWISE),
            )
        Orientation.ALL.forEach { orientation ->
            val rotation = FasterLastLayer.rotationToTop(orientation)
            val up =
                if (rotation == null) {
                    orientation
                } else {
                    CubeState.SOLVED
                        .reoriented(orientation)
                        .apply(rotation)
                        .orientation
                }
            assertEquals(Color.YELLOW, up.colorAt(Face.U), "$orientation")
            val yellowAt = Face.entries.first { orientation.colorAt(it) == Color.YELLOW }
            assertEquals(expected[yellowAt], rotation, "$orientation")
            if (rotation != null && rotation.axis == Axis.Z) assertEquals(orientation.front, up.front)
        }
        val state = lastLayer("R U R' U R U2 R'")
        Orientation.ALL.forEach { orientation ->
            val held = state.reoriented(orientation)
            faster.forEach { method -> assertTrue(held.apply(solved(held, method).flatMap { it.moves }).isSolved, "$method $orientation") }
        }
    }

    @Test
    fun `a lone AUF is one segment tagged with the method's last-layer Stage`() {
        val state = CubeState.SOLVED.apply(FaceTurn(Face.D, Turn.CLOCKWISE))
        faster.forEach { method ->
            val segments = solved(state, method)
            assertEquals(1, segments.size)
            assertEquals(SegmentTag.OfStage(MethodSolve.lastLayerStageOf(method)!!), segments.single().tag)
            assertEquals(z2, segments.single().moves.first())
            assertEquals(2, segments.single().moves.size)
            assertEquals(Face.U, (segments.single().moves.last() as FaceTurn).face)
            assertTrue(MethodSolve.conforms(segments, method, bundle))
            assertTrue(state.apply(segments.single().moves).isSolved)
        }
        assertEquals(StageId("stage-09-two-look-pll"), MethodSolve.lastLayerStageOf(SolveMethod.TWO_LOOK))
        assertEquals(StageId("stage-06-last-layer-completion"), MethodSolve.lastLayerStageOf(SolveMethod.FULL_OLL_PLL))
        assertEquals(null, MethodSolve.lastLayerStageOf(SolveMethod.BEGINNER))
    }

    @Test
    fun `a dot OLL uses the preferred 45 then 44 over a shorter OLL 02`() {
        val dot = lastLayer("F R U R' U' F' f R U R' U' f'")
        val preferred =
            mapOf(AlgorithmId("alg-oll-45-a") to AlgorithmStatus.LEARNING, AlgorithmId("alg-oll-44-a") to AlgorithmStatus.LEARNED)
        val withPreference = solved(dot, SolveMethod.TWO_LOOK, statuses = preferred)
        assertEquals(listOf("case-oll-45", "case-oll-44"), withPreference.take(2).map(::caseOf))
        assertTrue(withPreference.drop(2).none { caseOf(it) in setOf("case-oll-02", "case-oll-44", "case-oll-45") })
        assertTrue(dot.apply(withPreference.flatMap { it.moves }).isSolved)
        assertTrue(MethodSolve.conforms(withPreference, SolveMethod.TWO_LOOK, bundle, preferred))
        val plain = solved(dot, SolveMethod.TWO_LOOK)
        assertEquals("case-oll-02", caseOf(plain.first()))
        assertTrue(plain.drop(1).none { caseOf(it) in setOf("case-oll-02", "case-oll-44", "case-oll-45") })
        assertTrue(dot.apply(plain.flatMap { it.moves }).isSolved)
        // A Not started status is no preference.
        val notStarted = mapOf(AlgorithmId("alg-oll-45-a") to AlgorithmStatus.NOT_STARTED)
        assertEquals(plain, solved(dot, SolveMethod.TWO_LOOK, statuses = notStarted))
    }

    @Test
    fun `full OLL + PLL plays a preferred alternative Algorithm of the Case`() {
        val alternative = Algorithm(AlgorithmId("alg-oll-45-b"), CaseId("case-oll-45"), "y2 B L U L' U' B' y2")
        val content = bundle.copy(algorithms = bundle.algorithms + alternative)
        val state = lastLayer("F R U R' U' F'")
        val statuses = mapOf(alternative.id to AlgorithmStatus.LEARNING)
        val chosen = solved(state, SolveMethod.FULL_OLL_PLL, content, statuses)
        val first = chosen.first()
        assertEquals("case-oll-45", caseOf(first))
        assertTrue(first.moves.windowed(moves(alternative.notation).size).any { it == moves(alternative.notation) }, "${first.moves}")
        assertTrue(state.apply(chosen.flatMap { it.moves }).isSolved)
        assertTrue(MethodSolve.conforms(chosen, SolveMethod.FULL_OLL_PLL, content, statuses))
        // The same moves are not the chosen set without the status, which picks the primary instead.
        assertFalse(MethodSolve.conforms(chosen, SolveMethod.FULL_OLL_PLL, content))
        val primary = solved(state, SolveMethod.FULL_OLL_PLL, content)
        assertTrue(
            primary
                .first()
                .moves
                .windowed(6)
                .any { it == moves("F R U R' U' F'") },
            "${primary.first().moves}",
        )
    }

    @Test
    fun `every set Algorithm keeps yellow on U`() {
        faster.forEach { method ->
            val looks = FasterLastLayer.looksOf(method, bundle, emptyMap())!!
            assertTrue(looks.isNotEmpty())
            looks.flatMap { it.algorithms }.forEach { algorithm ->
                val after = CubeState.SOLVED.reoriented(yellowUp).apply(algorithm.moves)
                assertEquals(Color.YELLOW, after.orientation.colorAt(Face.U), algorithm.algorithm.id.value)
            }
        }
        assertEquals(
            listOf("edges-oriented", "oriented", "corners-permuted", "solved-up-to-auf").map { it.replace('-', '_').uppercase() },
            FasterLastLayer.looksOf(SolveMethod.TWO_LOOK, bundle, emptyMap())!!.map { it.step.name },
        )
        assertEquals(57 + 21, FasterLastLayer.looksOf(SolveMethod.FULL_OLL_PLL, bundle, emptyMap())!!.sumOf { it.algorithms.size })
    }

    @Test
    fun `2-look and full stop when told and are deterministic`() {
        faster.forEach { method ->
            assertEquals(MethodSolveResult.Stopped, solve(random(900), method) { false })
            assertEquals(MethodSolveResult.Stopped, solve(lastLayer("R U R' U R U2 R'"), method) { false })
            val state = random(901)
            assertEquals(solve(state, method), solve(state, method))
        }
    }

    @Test
    fun `content without a last-layer set is NotFound`() {
        val state = random(902)
        assertEquals(MethodSolveResult.NotFound, solve(state, SolveMethod.TWO_LOOK, bundle.copy(twoLookStages = emptyList())))
        assertEquals(MethodSolveResult.NotFound, solve(state, SolveMethod.FULL_OLL_PLL, bundle.copy(algorithms = emptyList())))
        val noStage = bundle.copy(stages = bundle.stages.filter { it.id != MethodSolve.FIRST_TWO_LAYER_STAGES.first() })
        faster.forEach { assertEquals(MethodSolveResult.NotFound, solve(state, it, noStage)) }
    }

    @Test
    fun `conforms rejects a Case segment with a non-set Algorithm or the wrong Case`() {
        val state = lastLayer("R U R' U R U2 R'", "R U R' U' R' F R2 U' R' U' R U R' F'")
        faster.forEach { method ->
            val segments = solved(state, method)
            assertTrue(MethodSolve.conforms(segments, method, bundle))
            val wrongCase =
                segments.mapIndexed { index, it ->
                    if (index ==
                        0
                    ) {
                        it.copy(tag = SegmentTag.OfCase(CaseId("case-pll-h")))
                    } else {
                        it
                    }
                }
            assertFalse(MethodSolve.conforms(wrongCase, method, bundle))
            val nonSet =
                segments.mapIndexed { index, it ->
                    if (index ==
                        0
                    ) {
                        it.copy(moves = it.moves + FaceTurn(Face.R, Turn.CLOCKWISE))
                    } else {
                        it
                    }
                }
            assertFalse(MethodSolve.conforms(nonSet, method, bundle))
            val tooManyRotations =
                segments.mapIndexed { index, it ->
                    if (index ==
                        segments.lastIndex
                    ) {
                        it.copy(moves = listOf(z2) + it.moves)
                    } else {
                        it
                    }
                }
            if (segments.size > 1) assertFalse(MethodSolve.conforms(tooManyRotations, method, bundle))
            assertFalse(MethodSolve.conforms(segments, SolveMethod.BEGINNER, bundle))
        }
        // Full needs the full set: a 2-look-only OLL 02 segment is fine there, a 2-look PLL T segment too; an OLL 01 is not 2-look.
        val oll01 = listOf(MethodSegment(SegmentTag.OfCase(CaseId("case-oll-01")), moves("R U2 R2 F R F' U2 R' F R F'")))
        assertTrue(MethodSolve.conforms(oll01, SolveMethod.FULL_OLL_PLL, bundle))
        assertFalse(MethodSolve.conforms(oll01, SolveMethod.TWO_LOOK, bundle))
        val lone = listOf(MethodSegment(SegmentTag.OfStage(MethodSolve.TWO_LOOK_LAST_LAYER_STAGE), listOf(z2, FaceTurn(Face.U, Turn.HALF))))
        assertTrue(MethodSolve.conforms(lone, SolveMethod.TWO_LOOK, bundle))
        assertFalse(MethodSolve.conforms(lone, SolveMethod.FULL_OLL_PLL, bundle))
        assertFalse(MethodSolve.conforms(listOf(lone.single().copy(moves = listOf(z2))), SolveMethod.TWO_LOOK, bundle))
    }

    @Test
    fun `held yellow up, an Algorithm that starts with its own rotation conforms`() {
        listOf("x R' U R' D2 R U' R' D2 R2 x'", "x' L' U L D' L' U' L D L' U' L D' L' U L D x").forEach { pll ->
            val state = lastLayer(pll).reoriented(yellowUp)
            val segments = solved(state, SolveMethod.FULL_OLL_PLL)
            assertTrue(segments.first().moves.first() is CubeRotation, "${segments.first().moves}")
            assertTrue(MethodSolve.conforms(segments, SolveMethod.FULL_OLL_PLL, bundle), "${segments.first().moves}")
            assertTrue(state.apply(segments.flatMap { it.moves }).isSolved)
        }
    }

    @Test
    fun `2-look and full stop inside a look's search`() {
        val dot = lastLayer("F R U R' U' F' f R U R' U' f'")
        faster.forEach { method ->
            var calls = 0
            val result =
                solve(dot, method) {
                    calls++
                    calls <= 1
                }
            assertEquals(MethodSolveResult.Stopped, result, "$method")
            assertTrue(calls > 1, "$method")
        }
    }
}
