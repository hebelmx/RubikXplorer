package dev.cubentia.app.di

import android.content.Context
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.content.CatalogState
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.solver.MethodSolveWarmUp
import dev.cubentia.app.solver.MethodSolverFactory
import dev.cubentia.app.solver.PruningTableSolverSource
import dev.cubentia.app.solver.SolveDeadline
import dev.cubentia.app.solver.SolverSource
import dev.cubentia.application.port.AlgorithmStatusRepository
import dev.cubentia.application.usecase.SolveByMethod
import dev.cubentia.solver.PruningTableProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

/**
 * Production pruning-table delivery, selected by Story 3.8 from Galaxy Tab S7 FE 5G measurements
 * (architecture memlog, docs/pruning-tables.md): **bundled**. The versioned, checksummed table file
 * ships as the APK asset [TABLE_ASSET] and is read and validated on the I/O dispatcher on the first
 * solve of a process. Only if that asset is unreadable or invalid does the provider fall back to
 * first-run generation into app-private, non-evictable `filesDir` ([fallbackFile]). Nothing here
 * runs at app startup or for a Lesson, and constructing the provider touches no disk: the path comes
 * from `applicationInfo.dataDir` (a field), because `Context.getFilesDir()` stats and creates the
 * directory on the calling (main) thread — a StrictMode violation found on the Tab S7 FE.
 */
@Module
@InstallIn(SingletonComponent::class)
object SolverModule {
    const val TABLE_ASSET = "solver/pruning-v1.bin"
    private const val TAG = "MethodSolveWarmUp"

    /** `<dataDir>/files/solver/pruning-v1.bin`, i.e. under `filesDir`, built without disk access. */
    fun fallbackFile(dataDir: File): File = File(dataDir, "files/solver/pruning-v1.bin")

    @Provides @Singleton
    fun provider(
        @ApplicationContext context: Context,
    ): PruningTableProvider =
        PruningTableProvider(
            fallbackFile(File(context.applicationInfo.dataDir)),
            bundled = { context.assets.open(TABLE_ASSET) },
        )

    @Provides @Singleton
    fun source(provider: PruningTableProvider): SolverSource = PruningTableSolverSource(provider)

    /** The one solve deadline of this process (Story 15.4): 3 s, or 5 s in a debuggable build. */
    @Provides @Singleton
    fun solveDeadline(
        @ApplicationContext context: Context,
    ): SolveDeadline = SolveDeadline.of(context.applicationInfo)

    /**
     * The one method-solve warm-up of this process (Story 15.4, AD-21): started by the solve flow once its pruning tables
     * are ready, never at app startup. Its content comes from [catalog]; a failure is logged in debuggable builds only.
     */
    @Provides @Singleton
    fun warmUp(
        @ApplicationContext context: Context,
        catalog: LessonCatalog,
    ): MethodSolveWarmUp {
        val debuggable = SolveDeadline.isDebuggable(context.applicationInfo)
        return MethodSolveWarmUp(
            bundle = {
                catalog.load()
                (catalog.state.value as? CatalogState.Ready)?.bundle
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            log = { message, failure -> if (debuggable) Log.d(TAG, message, failure) },
        )
    }

    /** The method-shaped solve for Solution Playback, preferring the Learner's *Learning* or *Learned* Algorithms (Story 15.3). */
    @Provides @Singleton
    fun methodSolverFactory(
        repository: AlgorithmStatusRepository,
        deadline: SolveDeadline,
    ): MethodSolverFactory = MethodSolverFactory { SolveByMethod(it, deadlineMillis = deadline.millis, algorithmStatuses = repository) }
}
