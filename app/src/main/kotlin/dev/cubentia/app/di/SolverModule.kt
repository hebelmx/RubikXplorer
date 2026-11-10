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

/** Interim private cache; Story 3.8 owns the production table delivery policy. */
@Module
@InstallIn(SingletonComponent::class)
object SolverModule {
    @Provides @Singleton
    fun provider(
        @ApplicationContext context: Context,
    ): PruningTableProvider = PruningTableProvider(File(context.cacheDir, "solver/pruning-v1.bin"))

    @Provides @Singleton
    fun source(provider: PruningTableProvider): SolverSource = PruningTableSolverSource(provider)
}
