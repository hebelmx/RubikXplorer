package dev.cubentia.domain.method

import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.predicate.Predicate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Story 15.1 (FR-30, AD-21): the beginner method-shaped solution, one segment per Course Stage. */
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
    ): MethodSolveResult = MethodSolve.solve(state, SolveMethod.BEGINNER, bundle, shouldContinue)

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
                    segment.moves.zipWithNext().forEach { (a, b) -> assertFalse(a.face == b.face, "${segment.moves}") }
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
                segment.moves.zipWithNext().forEach { (a, b) ->
                    assertFalse(a.face == BeginnerLastLayer.LL_FACE && b.face == BeginnerLastLayer.LL_FACE, "${segment.moves}")
                }
                assertTrue(splits(segment.moves, algorithms.getValue(stageOf(segment))), "${segment.moves}")
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
}
