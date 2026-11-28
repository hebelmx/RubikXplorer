package dev.cubentia.app.solver

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.viewModelScope
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.di.SolverModule
import dev.cubentia.app.ui.solve.SolveFlowViewModel
import dev.cubentia.app.ui.solve.SolvePhase
import dev.cubentia.application.port.AlgorithmStatusLoad
import dev.cubentia.application.port.AlgorithmStatusRecord
import dev.cubentia.application.port.AlgorithmStatusRepository
import dev.cubentia.application.port.AlgorithmStatusSave
import dev.cubentia.application.port.CubeSolver
import dev.cubentia.application.usecase.MethodSolveFunction
import dev.cubentia.application.usecase.PlanRecovery
import dev.cubentia.application.usecase.SolveByMethod
import dev.cubentia.application.usecase.SolveCube
import dev.cubentia.domain.content.ContentLoadError
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.method.MethodSolveResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.time.TimeSource

/** Story 15.4 (AD-8): one solve deadline per process, 3 s or 5 s when debuggable, passed to every deadline-bound use case. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SolveDeadlineTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle

    private val repository =
        object : AlgorithmStatusRepository {
            override fun statuses(): Flow<AlgorithmStatusLoad> = flowOf(AlgorithmStatusLoad.Loaded(emptyMap()))

            override suspend fun append(record: AlgorithmStatusRecord): AlgorithmStatusSave = AlgorithmStatusSave.Saved
        }

    @Before fun main() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After fun reset() {
        Dispatchers.resetMain()
    }

    private fun info(debuggable: Boolean): ApplicationInfo =
        ApplicationInfo().apply { flags = if (debuggable) ApplicationInfo.FLAG_DEBUGGABLE else 0 }

    @Test fun releaseIsThreeSecondsAndDebuggableFive() {
        assertEquals(3_000L, SolveDeadline.of(false).millis)
        assertEquals(5_000L, SolveDeadline.of(true).millis)
        assertEquals(SolveDeadline.RELEASE, SolveDeadline.of(false))
        assertEquals(SolveDeadline.RELEASE_MILLIS, SolveCube.DEADLINE_MILLIS)
        assertEquals(SolveDeadline.RELEASE_MILLIS, SolveByMethod.DEADLINE_MILLIS)
        assertEquals(SolveDeadline.RELEASE_MILLIS, PlanRecovery.DEADLINE_MILLIS)
    }

    @Test fun applicationInfoFlagDebuggableChoosesTheValue() {
        assertEquals(3_000L, SolveDeadline.of(info(debuggable = false)).millis)
        assertEquals(5_000L, SolveDeadline.of(info(debuggable = true)).millis)
        val context = RuntimeEnvironment.getApplication()
        context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        assertEquals(5_000L, SolverModule.solveDeadline(context).millis)
        context.applicationInfo.flags = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        assertEquals(3_000L, SolverModule.solveDeadline(context).millis)
    }

    @Test fun theProductionWarmUpLogsOnlyInDebuggableBuilds() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val failed = ContentLoadResult.Failed(ContentLoadError.MissingResource("bundle.json"))

            fun logged(debuggable: Boolean): Boolean {
                ShadowLog.clear()
                context.applicationInfo.flags =
                    if (debuggable) {
                        context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
                    } else {
                        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
                    }
                runBlocking { withTimeout(5_000) { checkNotNull(SolverModule.warmUp(context, LessonCatalog.of(failed)).start()).join() } }
                return ShadowLog.getLogsForTag("MethodSolveWarmUp").isNotEmpty()
            }
            assertTrue(logged(debuggable = true))
            assertFalse(logged(debuggable = false))
            ShadowLog.clear()
        }

    @Test fun theMethodSolverFactoryPassesTheDeadline() {
        assertEquals(5_000L, SolverModule.methodSolverFactory(repository, SolveDeadline(5_000)).create(bundle).deadlineMillis)
        assertEquals(3_000L, SolverModule.methodSolverFactory(repository, SolveDeadline(3_000)).create(bundle).deadlineMillis)
    }

    @Test fun theSolveFlowPassesItsDeadlineToSolveCube() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            val warmUp =
                MethodSolveWarmUp(
                    bundle = { bundle },
                    scope = scope,
                    solver = MethodSolveFunction { _, _, _, _, _ -> MethodSolveResult.Solved(emptyList()) },
                    log = { _, _ -> },
                )
            val source =
                object : SolverSource {
                    override val isReady = true

                    override suspend fun load(onGenerating: () -> Unit) = SolverLoad.Ready(CubeSolver { awaitCancellation() })
                }
            val vm = SolveFlowViewModel(source, SolveDeadline(50), warmUp)
            try {
                val cube = CubeState.SOLVED.apply(FaceTurn(Face.R, Turn.CLOCKWISE))
                val started = TimeSource.Monotonic.markNow()
                vm.accept(cube) { _, _ -> error("unexpected playback") }
                val editing = withTimeout(5_000) { vm.phase.first { it is SolvePhase.Editing } as SolvePhase.Editing }
                // Well under the 3 s release deadline: the injected 50 ms one ran.
                assertTrue(started.elapsedNow().inWholeMilliseconds < 2_500)
                assertEquals(cube.toFacelets(), editing.initial)
                assertEquals("msg.solve.timed-out", editing.error?.key)
            } finally {
                vm.viewModelScope.coroutineContext.job
                    .cancel()
                scope.cancel()
            }
        }
}
