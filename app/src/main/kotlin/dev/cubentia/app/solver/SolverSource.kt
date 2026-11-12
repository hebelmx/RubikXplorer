package dev.cubentia.app.solver

import dev.cubentia.application.port.CubeSolver
import dev.cubentia.solver.PruningTableLoadResult
import dev.cubentia.solver.PruningTableProvider
import dev.cubentia.solver.TwoPhaseCubeSolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SolverSource {
    val isReady: Boolean

    suspend fun load(onGenerating: () -> Unit = {}): SolverLoad
}

sealed interface SolverLoad {
    data class Ready(
        val solver: CubeSolver,
    ) : SolverLoad

    data object Failed : SolverLoad
}

class PruningTableSolverSource(
    private val provider: PruningTableProvider,
) : SolverSource {
    private val mutex = Mutex()

    @Volatile private var solver: CubeSolver? = null
    override val isReady: Boolean get() = solver != null

    override suspend fun load(onGenerating: () -> Unit): SolverLoad =
        mutex.withLock {
            solver?.let { return@withLock SolverLoad.Ready(it) }
            when (val loaded = provider.load(onGenerating)) {
                is PruningTableLoadResult.Ready -> SolverLoad.Ready(TwoPhaseCubeSolver(loaded.tables).also { solver = it })
                is PruningTableLoadResult.Failed -> SolverLoad.Failed
            }
        }
}
