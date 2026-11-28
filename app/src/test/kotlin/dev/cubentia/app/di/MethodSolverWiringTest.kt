package dev.cubentia.app.di

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.cubentia.app.data.RoomAlgorithmStatusRepository
import dev.cubentia.app.data.RubikDatabase
import dev.cubentia.app.solver.SolveDeadline
import dev.cubentia.application.session.Effect
import dev.cubentia.application.usecase.MethodSolveOutcome
import dev.cubentia.application.usecase.SetAlgorithmStatus
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.inverse
import dev.cubentia.domain.method.SegmentTag
import dev.cubentia.domain.method.SolveMethod
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * Story 15.3 (action item epic-15-deferred-15.2-wire-status-repository-in-15-3): the app's SolveByMethod, built through
 * SolverModule's provider, reads the real Room Algorithm Statuses, so a *Learning* Algorithm wins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MethodSolverWiringTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle

    /** First two layers solved, an edges-dot last layer (yellow on held D): OLL 02 alone, or OLL 45 then 44. */
    private val dot: CubeState =
        CubeState.SOLVED
            .reoriented(Orientation.of(Face.D, Face.F))
            .apply((Notation.parse("F R U R' U' F' f R U R' U' f'") as NotationResult.Parsed).moves.inverse())
            .reoriented(Orientation.HOME)

    private fun cases(repository: RoomAlgorithmStatusRepository): List<String> =
        runBlocking {
            val completed =
                SolverModule
                    .methodSolverFactory(
                        repository,
                        SolveDeadline.RELEASE,
                    ).create(bundle)(Effect.MethodSolve(1L, 4L, dot, SolveMethod.TWO_LOOK))
            assertEquals(1L, completed.effectId)
            assertEquals(4L, completed.revision)
            (completed.outcome as MethodSolveOutcome.Solved).segments.mapNotNull { (it.tag as? SegmentTag.OfCase)?.caseId?.value }
        }

    @Test fun learningAlgorithmsInRoomWinOverAShorterOne() {
        val db =
            Room
                .inMemoryDatabaseBuilder<RubikDatabase>(RuntimeEnvironment.getApplication())
                .setDriver(AndroidSQLiteDriver())
                .build()
        try {
            val repository = RoomAlgorithmStatusRepository(db)
            assertEquals("case-oll-02", cases(repository).first())
            val set = SetAlgorithmStatus(repository)
            val at = Instant.fromEpochMilliseconds(0)
            runBlocking {
                set(AlgorithmId("alg-oll-45-a"), AlgorithmStatus.LEARNING, at, "run", 0L)
                set(AlgorithmId("alg-oll-44-a"), AlgorithmStatus.LEARNING, at, "run", 1L)
            }
            assertEquals(listOf("case-oll-45", "case-oll-44"), cases(repository).take(2))
        } finally {
            db.close()
        }
    }
}
