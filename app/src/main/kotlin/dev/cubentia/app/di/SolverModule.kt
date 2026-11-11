package dev.cubentia.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.solver.PruningTableSolverSource
import dev.cubentia.app.solver.SolverSource
import dev.cubentia.solver.PruningTableProvider
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
}
