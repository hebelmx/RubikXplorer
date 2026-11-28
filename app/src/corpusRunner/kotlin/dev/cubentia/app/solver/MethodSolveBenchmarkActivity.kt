package dev.cubentia.app.solver

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import dev.cubentia.app.di.SolverModule
import dev.cubentia.application.session.Effect
import dev.cubentia.application.usecase.MethodSolveOutcome
import dev.cubentia.application.usecase.PlanRecovery
import dev.cubentia.application.usecase.SolveByMethod
import dev.cubentia.application.usecase.SolveCube
import dev.cubentia.application.usecase.SolveCubeOutcome
import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.FaceletString
import dev.cubentia.domain.content.FaceletStringResult
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.method.SolveMethod
import dev.cubentia.domain.tutor.RecoveryPlanner
import dev.cubentia.solver.PruningTableLoadResult
import dev.cubentia.solver.PruningTableProvider
import dev.cubentia.solver.TwoPhaseCubeSolver
import dev.cubentia.solver.benchmark.randomLegalState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Solver-deadline evidence runner (sprint change proposal 2026-11-27, solver deadline tier). Times the
 * three deadline-bound use cases exactly as the app calls them: [SolveByMethod] (BEGINNER, TWO_LOOK,
 * FULL_OLL_PLL) and [PlanRecovery] without a deadline, so a slow solve shows its real time instead of
 * stopping at 3 s, and [SolveCube] with its own fixed 3 s deadline. Content and pruning tables are
 * prepared before the first timed solve, outside every timing, as in the app. Never starts with the
 * app; compiled into `debug` and `benchmark` only, never `release`.
 *
 * For the method kinds, the production [MethodSolveWarmUp] (Story 15.4) then runs once to completion, as the app runs it
 * after preparing the solver, and logs `warmed-up,nanos=`; it comes before the untimed [warmup] repeats and the first
 * timed solve. Pass `--ez warmUp false` to skip it and reproduce the "before" rows of `docs/benchmarks.md`.
 *
 * - cold: a new process (force-stop first) whose first [count] solves are timed, the first being JIT-cold;
 * - warm: [warmup] untimed solves, then [cases] timed ones in the same process.
 *
 * `adb shell am start -W -n com.exxerpro.cubentia/dev.cubentia.app.solver.MethodSolveBenchmarkActivity
 *   --es kind TWO_LOOK --es mode cold --ei index 0 --ei count 3`
 *
 * `kind` is BEGINNER, TWO_LOOK, FULL_OLL_PLL, SOLVE_CUBE or PLAN_RECOVERY; output is logcat tag
 * `MethodSolveBenchmark`, one `solve,...` line per timed solve, then `done`.
 */
class MethodSolveBenchmarkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val kind = intent.getStringExtra("kind") ?: SolveMethod.TWO_LOOK.name
        val mode = intent.getStringExtra("mode") ?: "cold"
        val seed = intent.getLongExtra("seed", DEFAULT_SEED)
        val index = intent.getIntExtra("index", 0)
        val count = intent.getIntExtra("count", if (mode == "cold") 3 else 100)
        val cases = intent.getIntExtra("cases", count)
        val warmup = intent.getIntExtra("warmup", if (mode == "cold") 0 else 20)
        val warmUp = intent.getBooleanExtra("warmUp", true)
        Log.i(TAG, "identity,fingerprint=${Build.FINGERPRINT},model=${Build.MODEL},cpus=${Runtime.getRuntime().availableProcessors()}")
        Log.i(TAG, "args,kind=$kind,mode=$mode,seed=$seed,index=$index,cases=$cases,warmup=$warmup")
        Log.i(TAG, "build,debuggable=${applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0}")
        Log.i(TAG, conditions("start"))
        thread(name = "method-solve-benchmark") {
            try {
                runBlocking { run(kind, mode, seed, index, cases, warmup, warmUp) }
                Log.i(TAG, conditions("end"))
                Log.i(TAG, "done")
            } catch (failure: Exception) {
                Log.e(TAG, "measurement-incomplete", failure)
            }
            runOnUiThread { finish() }
        }
    }

    private suspend fun run(
        kind: String,
        mode: String,
        seed: Long,
        index: Int,
        cases: Int,
        warmup: Int,
        warmUp: Boolean,
    ) {
        val prepareStart = System.nanoTime()
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val timed: suspend (Int) -> String =
            when (kind) {
                SOLVE_CUBE -> {
                    val provider =
                        PruningTableProvider(
                            SolverModule.fallbackFile(File(applicationInfo.dataDir)),
                            bundled = { assets.open(SolverModule.TABLE_ASSET) },
                        )
                    val tables = (provider.load() as PruningTableLoadResult.Ready).tables
                    val useCase = SolveCube(TwoPhaseCubeSolver(tables))
                    val solve: suspend (Int) -> String = { i ->
                        when (val outcome = useCase(Effect.SolveCube(1, 1, state(seed, i))).outcome) {
                            is SolveCubeOutcome.Solved -> "Solved,moves=${outcome.moves.size}"
                            is SolveCubeOutcome.TimedOut -> "TimedOut,moves=0"
                            is SolveCubeOutcome.Failed -> "Failed,moves=0"
                        }
                    }
                    solve
                }

                PLAN_RECOVERY -> {
                    val useCase = PlanRecovery(deadlineMillis = Long.MAX_VALUE)
                    val solve: suspend (Int) -> String = { i ->
                        val moves = useCase(recovery(bundle, seed, i))
                        if (moves == null) "NoPlan,moves=0" else "Found,moves=${moves.size}"
                    }
                    solve
                }

                else -> {
                    val method = SolveMethod.valueOf(kind)
                    val useCase = SolveByMethod(bundle, deadlineMillis = Long.MAX_VALUE)
                    val solve: suspend (Int) -> String = { i ->
                        when (val outcome = useCase(Effect.MethodSolve(1, 1, state(seed, i), method)).outcome) {
                            is MethodSolveOutcome.Solved -> "Solved,moves=${outcome.segments.sumOf { it.moves.size }}"
                            MethodSolveOutcome.TimedOut -> "TimedOut,moves=0"
                            MethodSolveOutcome.Failed -> "Failed,moves=0"
                        }
                    }
                    solve
                }
            }
        Log.i(TAG, "prepared,nanos=${System.nanoTime() - prepareStart}")
        if (warmUp && kind != SOLVE_CUBE && kind != PLAN_RECOVERY) {
            // The production warm-up (Story 15.4), run to completion before any timed solve.
            val warmStart = System.nanoTime()
            coroutineScope {
                MethodSolveWarmUp(
                    bundle = { bundle },
                    scope = this,
                    log = { message, failure -> Log.i(TAG, "warm-up-note,$message", failure) },
                ).start()?.join()
            }
            Log.i(TAG, "warmed-up,nanos=${System.nanoTime() - warmStart}")
        }
        // Warmup cases come from a disjoint index range so they never repeat a timed case.
        repeat(warmup) { timed(WARMUP_OFFSET + it) }
        val all = mutableListOf<Long>()
        for (ordinal in 1..cases) {
            val i = index + ordinal - 1
            val start = System.nanoTime()
            val result = timed(i)
            val nanos = System.nanoTime() - start
            all += nanos
            Log.i(TAG, "solve,kind=$kind,mode=$mode,ordinal=$ordinal,index=$i,nanos=$nanos,outcome=$result")
        }
        // One line with every time, in case logcat drops a per-solve line.
        for ((part, chunk) in all.chunked(SUMMARY_CHUNK).withIndex()) {
            Log.i(TAG, "summary,kind=$kind,mode=$mode,part=$part,nanos=${chunk.joinToString(";")}")
        }
    }

    /** Case [i] of [seed]: a uniform random legal cube, like a scanned one. */
    private fun state(
        seed: Long,
        i: Int,
    ): CubeState = randomLegalState(Random(seed + i))

    /** Case [i] of [seed]: a Lesson's entry state after 1..4 random Moves from the tutor's Move set. */
    private fun recovery(
        bundle: ContentBundle,
        seed: Long,
        i: Int,
    ): Effect.PlanRecovery {
        val random = Random(seed + i)
        val lessons = bundle.stages.flatMap { it.lessons }
        val lesson = lessons[random.nextInt(lessons.size)]
        val facelets = (FaceletString.decode(lesson.entryState) as FaceletStringResult.Decoded).facelets
        val entry = (CubeValidator.validate(facelets) as ValidationResult.Valid).state
        val set = bundle.tutorConfig.moves
        val played = List(1 + random.nextInt(4)) { set[random.nextInt(set.size)] }
        return Effect.PlanRecovery(
            effectId = 1,
            revision = 1,
            state = entry.apply(played),
            goal = RecoveryPlanner.goal(lesson, listOf(entry, entry)),
            maxDepth = bundle.tutorConfig.recoveryMaxDepth,
            moveSet = set,
        )
    }

    private fun conditions(stage: String): String {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val level = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val power = getSystemService(PowerManager::class.java)
        return "conditions,$stage,plugged=$plugged,batteryPercent=$level,thermalStatus=${power.currentThermalStatus}," +
            "powerSave=${power.isPowerSaveMode}"
    }

    private companion object {
        const val TAG = "MethodSolveBenchmark"
        const val SOLVE_CUBE = "SOLVE_CUBE"
        const val PLAN_RECOVERY = "PLAN_RECOVERY"
        const val DEFAULT_SEED = 20_261_127L
        const val WARMUP_OFFSET = 1_000_000
        const val SUMMARY_CHUNK = 50
    }
}
