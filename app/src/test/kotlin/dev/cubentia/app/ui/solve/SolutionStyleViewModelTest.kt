package dev.cubentia.app.ui.solve

import androidx.lifecycle.viewModelScope
import dev.cubentia.app.solver.MethodSolutionCache
import dev.cubentia.app.solver.MethodSolverFactory
import dev.cubentia.application.usecase.MethodSolveFunction
import dev.cubentia.application.usecase.SolveByMethod
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.method.MethodSegment
import dev.cubentia.domain.method.MethodSolve
import dev.cubentia.domain.method.MethodSolveResult
import dev.cubentia.domain.method.SegmentTag
import dev.cubentia.domain.method.SolveMethod
import dev.cubentia.domain.text.Text
import dev.cubentia.domain.tutor.ProgressEvent
import dev.cubentia.domain.tutor.StageAttempt
import dev.cubentia.domain.tutor.TwoLookMastery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Story 15.3: the *Solution style* rules of the I/O matrix, over the real SolveByMethod with a controllable search. */
@OptIn(ExperimentalCoroutinesApi::class)
class SolutionStyleViewModelTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val scopes = mutableListOf<Job>()
    private val cache = MethodSolutionCache()

    private val move = FaceTurn(Face.R, Turn.CLOCKWISE)
    private val input = CubeState.SOLVED.apply(move)
    private val shortest: List<Move> = listOf(move.inverse())

    /** Replaces the real method solve when set. */
    @Volatile private var fake: MethodSolveResult? = null
    private val held = AtomicBoolean(false)
    private val searches = AtomicInteger(0)
    private var deadline = 3_000L
    private var revision = 7L
    private val restarts: MutableList<List<Move>> = Collections.synchronizedList(mutableListOf())

    private val search =
        MethodSolveFunction { state, method, content, statuses, shouldContinue ->
            searches.incrementAndGet()
            while (held.get() && shouldContinue()) Thread.sleep(2)
            if (!shouldContinue()) {
                MethodSolveResult.Stopped
            } else {
                fake ?: MethodSolve.solve(state, method, content, statuses, shouldContinue)
            }
        }

    // Real time on Main, so SolveByMethod's deadline elapses.
    @BeforeEach fun main() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterEach fun reset() {
        held.set(false)
        runBlocking {
            scopes.forEach { it.cancel() }
            scopes.joinAll()
        }
        Dispatchers.resetMain()
    }

    private fun viewModel(): SolutionStyleViewModel =
        SolutionStyleViewModel(MethodSolverFactory { SolveByMethod(it, deadline, search) }, cache)
            .also { scopes += it.viewModelScope.coroutineContext.job }
            .also { it.opened(input, shortest, fromAcceptedScan = false) }

    private fun SolutionStyleViewModel.tap(style: SolutionStyle) = choose(style, bundle, { revision }) { restarts += it }

    private fun SolutionStyleViewModel.awaitState(predicate: (SolutionStyleState) -> Boolean): SolutionStyleState =
        runBlocking { withTimeout(10_000) { state.first(predicate) } }

    private fun awaitRestarts(count: Int) = runBlocking { withTimeout(10_000) { while (restarts.size < count) delay(5) } }

    @Test fun opensOnShortestWithNothingBuilding() {
        val vm = viewModel()
        assertEquals(SolutionStyleState(), vm.state.value)
        assertEquals(input, vm.input)
    }

    @Test fun aBuiltMethodRestartsPlaybackOnItsReVerifiedMovesAndKeepsItsSegments() {
        held.set(true)
        val vm = viewModel()
        vm.tap(SolutionStyle.BEGINNER)
        val pending = vm.state.value
        assertEquals(SolutionStyle.BEGINNER, pending.selected)
        assertEquals(SolutionStyle.BEGINNER, pending.building)
        assertEquals(SolutionStyle.SHORTEST, pending.playing)
        assertTrue(restarts.isEmpty())
        held.set(false)
        val loaded = vm.awaitState { it.building == null }
        assertEquals(SolutionStyle.BEGINNER, loaded.selected)
        assertEquals(SolutionStyle.BEGINNER, loaded.playing)
        assertNull(loaded.message)
        assertTrue(loaded.segments.isNotEmpty())
        assertTrue(loaded.segments.all { it.tag is SegmentTag.OfStage })
        awaitRestarts(1)
        assertEquals(loaded.segments.flatMap { it.moves }, restarts.single())
        assertTrue(input.apply(restarts.single()).isSolved)
    }

    @Test fun aMethodChosenAgainForTheSameInputLoadsFromTheCacheWithoutABuild() {
        val vm = viewModel()
        vm.tap(SolutionStyle.BEGINNER)
        vm.awaitState { it.playing == SolutionStyle.BEGINNER }
        awaitRestarts(1)
        vm.tap(SolutionStyle.SHORTEST)
        awaitRestarts(2)
        assertEquals(shortest, restarts[1])
        assertEquals(SolutionStyleState(), vm.state.value)
        vm.tap(SolutionStyle.BEGINNER)
        assertEquals(SolutionStyle.BEGINNER, vm.state.value.playing)
        assertNull(vm.state.value.building)
        awaitRestarts(3)
        assertEquals(restarts[0], restarts[2])
        // A later playback of the same input in this app process reuses it too.
        val again = viewModel()
        again.tap(SolutionStyle.BEGINNER)
        assertEquals(SolutionStyle.BEGINNER, again.state.value.playing)
        assertEquals(1, searches.get())
    }

    @Test fun tappingShortestDuringABuildCancelsItWithoutRestartingPlayback() {
        held.set(true)
        val vm = viewModel()
        vm.tap(SolutionStyle.BEGINNER)
        vm.tap(SolutionStyle.SHORTEST)
        assertEquals(SolutionStyleState(), vm.state.value)
        held.set(false)
        Thread.sleep(100)
        assertTrue(restarts.isEmpty())
        assertEquals(SolutionStyleState(), vm.state.value)
    }

    @Test fun tappingAnotherMethodDuringABuildCancelsItAndBuildsTheNewOne() {
        held.set(true)
        val vm = viewModel()
        vm.tap(SolutionStyle.BEGINNER)
        vm.tap(SolutionStyle.TWO_LOOK)
        assertEquals(SolutionStyle.TWO_LOOK, vm.state.value.building)
        assertEquals(SolutionStyle.TWO_LOOK, vm.state.value.selected)
        held.set(false)
        val loaded = vm.awaitState { it.building == null }
        assertEquals(SolutionStyle.TWO_LOOK, loaded.playing)
        awaitRestarts(1)
        Thread.sleep(50)
        assertEquals(1, restarts.size)
    }

    @Test fun tappingTheBuildingChipAgainChangesNothing() {
        held.set(true)
        val vm = viewModel()
        vm.tap(SolutionStyle.BEGINNER)
        val pending = vm.state.value
        vm.tap(SolutionStyle.BEGINNER)
        assertEquals(pending, vm.state.value)
    }

    @Test fun aTimeoutReturnsToShortestWithTheTimeoutCopyUntilAChipIsTapped() {
        held.set(true)
        deadline = 50L
        val vm = viewModel()
        vm.tap(SolutionStyle.FULL_OLL_PLL)
        val shown = vm.awaitState { it.building == null }
        assertEquals(SolutionStyleState(message = Text("msg.playback.style.timed-out")), shown)
        assertTrue(restarts.isEmpty())
        // Tapping the chip already playing only clears the message.
        vm.tap(SolutionStyle.SHORTEST)
        assertEquals(SolutionStyleState(), vm.state.value)
        assertTrue(restarts.isEmpty())
    }

    @Test fun aFailureNamesTheMethodAndKeepsShortest() {
        fake = MethodSolveResult.NotFound
        val vm = viewModel()
        vm.tap(SolutionStyle.TWO_LOOK)
        val shown = vm.awaitState { it.building == null }
        assertEquals(SolutionStyle.SHORTEST, shown.selected)
        assertEquals(Text("msg.playback.style.failed", mapOf("method" to Text("msg.playback.style.two-look"))), shown.message)
        assertTrue(restarts.isEmpty())
    }

    @Test fun aResultThatFailsTheReCheckIsAFailure() {
        fake = MethodSolveResult.Solved(listOf(MethodSegment(SegmentTag.OfStage(MethodSolve.FIRST_TWO_LAYER_STAGES[0]), listOf(move))))
        val vm = viewModel()
        vm.tap(SolutionStyle.BEGINNER)
        val shown = vm.awaitState { it.building == null }
        assertEquals(Text("msg.playback.style.failed", mapOf("method" to Text("msg.playback.style.beginner"))), shown.message)
        assertTrue(restarts.isEmpty())
    }

    @Test fun aFailureWhileAMethodPlaysRestartsOnShortest() {
        val vm = viewModel()
        vm.tap(SolutionStyle.BEGINNER)
        vm.awaitState { it.playing == SolutionStyle.BEGINNER }
        awaitRestarts(1)
        fake = MethodSolveResult.NotFound
        vm.tap(SolutionStyle.TWO_LOOK)
        val shown = vm.awaitState { it.building == null }
        assertEquals(SolutionStyle.SHORTEST, shown.playing)
        assertEquals(SolutionStyle.SHORTEST, shown.selected)
        awaitRestarts(2)
        assertEquals(shortest, restarts[1])
    }

    @Test fun aResultForAnOlderSessionRevisionIsDiscardedWithNoMessage() {
        held.set(true)
        val vm = viewModel()
        vm.tap(SolutionStyle.BEGINNER)
        revision++
        held.set(false)
        val shown = vm.awaitState { it.building == null }
        assertEquals(SolutionStyleState(), shown)
        Thread.sleep(50)
        assertTrue(restarts.isEmpty())
    }

    @Test fun aSessionMoveCancelsAPendingBuildAtOnce() {
        held.set(true)
        val vm = viewModel()
        vm.tap(SolutionStyle.BEGINNER)
        vm.sessionMoved(revision)
        assertEquals(SolutionStyle.BEGINNER, vm.state.value.building)
        vm.sessionMoved(revision + 1)
        assertEquals(SolutionStyleState(), vm.state.value)
        held.set(false)
        Thread.sleep(100)
        assertTrue(restarts.isEmpty())
        assertEquals(SolutionStyleState(), vm.state.value)
    }

    @Test fun aUseMyCubeReSolveResetsTheStyleCancelsABuildAndClearsMessages() {
        val vm = viewModel()
        vm.tap(SolutionStyle.BEGINNER)
        vm.awaitState { it.playing == SolutionStyle.BEGINNER }
        held.set(true)
        vm.tap(SolutionStyle.TWO_LOOK)
        val observed = CubeState.SOLVED.apply(FaceTurn(Face.U, Turn.CLOCKWISE))
        vm.opened(observed, listOf(FaceTurn(Face.U, Turn.COUNTERCLOCKWISE)), fromAcceptedScan = true)
        assertEquals(SolutionStyleState(), vm.state.value)
        assertEquals(observed, vm.input)
        assertTrue(vm.fromAcceptedScan)
        held.set(false)
        Thread.sleep(100)
        assertEquals(SolutionStyleState(), vm.state.value)
        assertEquals(1, restarts.size)
        vm.left()
        assertNull(vm.input)
        assertEquals(SolutionStyleState(), vm.state.value)
    }

    @Test fun theSuggestionFollowsProgress() {
        assertEquals(SolutionStyle.BEGINNER, suggestedStyle(bundle, emptyList(), emptyList()))
        // Every Course Stage finished: Lessons completed, and a Stage with no Lessons (independent practice) passed by its check.
        val course =
            bundle.stages.flatMap { it.lessons }.map { ProgressEvent.LessonCompleted(it.id) } +
                bundle.stages.filter { it.lessons.isEmpty() }.map { ProgressEvent.StageSkippedByCheck(it.id) }
        assertEquals(SolutionStyle.TWO_LOOK, suggestedStyle(bundle, course, emptyList()))
        val twoLookSkipped = course + bundle.twoLookStages.map { ProgressEvent.StageSkippedByPlacement(it.id) }
        assertEquals(SolutionStyle.FULL_OLL_PLL, suggestedStyle(bundle, twoLookSkipped, emptyList()))
        val twoLookDone = course + bundle.twoLookStages.flatMap { it.lessons }.map { ProgressEvent.LessonCompleted(it.id) }
        assertEquals(SolutionStyle.TWO_LOOK, suggestedStyle(bundle, twoLookDone, emptyList()))
        val solves =
            List(bundle.tutorConfig.twoLookSolvesRequired) { StageAttempt(TwoLookMastery.PRACTICE_ID.value, hints = 0, errors = 0) }
        assertEquals(SolutionStyle.FULL_OLL_PLL, suggestedStyle(bundle, twoLookDone, solves))
    }

    @Test fun segmentLabelsNameEachSegmentsStageOrCaseFromContent() {
        val stage = MethodSolve.FIRST_TWO_LAYER_STAGES[0]
        val case = bundle.cases.first()
        val segments =
            listOf(
                MethodSegment(SegmentTag.OfStage(stage), listOf(move, move)),
                MethodSegment(SegmentTag.OfCase(case.id), listOf(move)),
            )
        val labels = segmentLabels(segments, bundle)!!
        val title =
            Text(
                bundle.stages
                    .single { it.id == stage }
                    .title.value,
            )
        assertEquals(listOf<Move>(move, move, move), labels.moves)
        assertEquals(title, labels.at(0))
        assertEquals(title, labels.at(1))
        assertEquals(Text(case.name.value), labels.at(2))
        assertNull(labels.at(3))
        assertNull(labels.at(null))
        assertNull(segmentLabels(emptyList(), bundle))
        assertEquals(SolveMethod.FULL_OLL_PLL, SolutionStyle.FULL_OLL_PLL.method)
        assertNull(SolutionStyle.SHORTEST.method)
    }
}
