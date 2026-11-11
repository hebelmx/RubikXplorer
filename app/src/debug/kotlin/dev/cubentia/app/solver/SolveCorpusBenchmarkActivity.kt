package dev.cubentia.app.solver

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import dev.cubentia.solver.benchmark.RuntimeHeapSampler
import dev.cubentia.solver.benchmark.SolveCorpusHarness
import dev.cubentia.solver.benchmark.SolverWorkload
import kotlin.concurrent.thread

/**
 * Story 3.8 debug-only device runner for the Story 3.3 corpus (calibration pair of
 * `:core:solver:solveCorpusBenchmark`): the same [SolverWorkload], seed, case count, warmup and
 * table-readiness boundary (tables generated before any timed attempt). Never starts with the app.
 *
 * `adb shell am start -n com.exxerpro.cubentia/dev.cubentia.app.solver.SolveCorpusBenchmarkActivity
 *   --ei cases 10000 --el seed 20261031 --ei warmup 200`
 */
class SolveCorpusBenchmarkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val cases = intent.getIntExtra("cases", DEFAULT_CASES)
        val seed = intent.getLongExtra("seed", DEFAULT_SEED)
        val warmup = intent.getIntExtra("warmup", SolveCorpusHarness.DEFAULT_WARMUP)
        Log.i(TAG, "device=${Build.MANUFACTURER}/${Build.MODEL},api=${Build.VERSION.SDK_INT},release=${Build.VERSION.RELEASE}")
        Log.i(TAG, "identity,fingerprint=${Build.FINGERPRINT},cpus=${Runtime.getRuntime().availableProcessors()}")
        Log.i(TAG, "args,cases=$cases,seed=$seed,warmup=$warmup")
        Log.i(TAG, conditions("start"))
        thread(name = "solve-corpus") {
            try {
                val prepareStart = System.nanoTime()
                val tables = SolverWorkload.preparedTables
                Log.i(TAG, "tablesReady,bytes=${tables.byteSize},prepareNanos=${System.nanoTime() - prepareStart}")
                val workload = SolverWorkload(cases, seed)
                val runStart = System.nanoTime()
                val stats = SolveCorpusHarness(warmup = warmup, heapSampler = RuntimeHeapSampler()).run(workload)
                val runtime = Runtime.getRuntime()
                stats.report(workload.name).lines().forEach { Log.i(TAG, "report,$it") }
                Log.i(
                    TAG,
                    "raw,cases=${stats.cases},medianNanos=${stats.medianNanos},p95Nanos=${stats.p95Nanos}," +
                        "maxNanos=${stats.maxNanos},withinBudget=${stats.withinBudget},failures=${stats.failures}," +
                        "peakHeapBytes=${stats.peakHeapBytes},javaHeapMaxBytes=${runtime.maxMemory()}," +
                        "wallNanos=${System.nanoTime() - runStart}",
                )
                Log.i(TAG, conditions("end"))
                Log.i(TAG, "done")
            } catch (failure: Exception) {
                Log.e(TAG, "measurement-incomplete", failure)
            }
            runOnUiThread { finish() }
        }
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
        const val TAG = "SolveCorpusBenchmark"
        const val DEFAULT_CASES = 10_000
        const val DEFAULT_SEED = 20_261_031L
    }
}
