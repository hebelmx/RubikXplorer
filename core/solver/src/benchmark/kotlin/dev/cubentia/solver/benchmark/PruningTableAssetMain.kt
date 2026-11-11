package dev.cubentia.solver.benchmark

import dev.cubentia.solver.PruningTableLoadResult
import dev.cubentia.solver.PruningTableProvider
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/** Story 3.8 temporary bundled candidate: writes the provider-format table file to `args[0]`. */
fun main(args: Array<String>) {
    val target = File(args.single())
    target.delete()
    when (val result = runBlocking { PruningTableProvider(target).load() }) {
        is PruningTableLoadResult.Ready -> {
            println("Wrote ${target.length()} bytes to $target")
        }

        is PruningTableLoadResult.Failed -> {
            System.err.println("Pruning table asset failed: ${result.reason}")
            exitProcess(1)
        }
    }
}
