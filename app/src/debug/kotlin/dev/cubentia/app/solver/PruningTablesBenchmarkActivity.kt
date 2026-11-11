package dev.cubentia.app.solver

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StrictMode
import android.util.Log
import dev.cubentia.app.di.SolverModule
import dev.cubentia.solver.PruningTableLoadResult
import dev.cubentia.solver.PruningTableProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** On-demand debug harness only; never starts with the app or a Lesson. */
class PruningTablesBenchmarkActivity : Activity() {
    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val violations = AtomicInteger()
    private var previousPolicy: StrictMode.ThreadPolicy? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previousPolicy = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .penaltyLog()
                .penaltyListener(Executor { it.run() }) { violations.incrementAndGet() }
                .build(),
        )
        scope.launch {
            try {
                val cache =
                    withContext(Dispatchers.IO) {
                        File(filesDir, "solver-benchmark/pruning-v1.bin").also {
                            if (intent.getBooleanExtra("cold", false) && it.exists()) {
                                check(it.delete()) { "Cannot remove benchmark cache" }
                            }
                        }
                    }
                Log.i(TAG, "device=${Build.MANUFACTURER}/${Build.MODEL},api=${Build.VERSION.SDK_INT}")
                Log.i(TAG, "identity,release=${Build.VERSION.RELEASE},fingerprint=${Build.FINGERPRINT}")
                Log.i(TAG, conditions())
                memory("before")
                if (intent.getBooleanExtra("bundledOnly", false)) {
                    // Story 3.8 production path alone in a fresh process: asset read + move-table build.
                    bundled(cache)
                    mainHandler.post {
                        Log.i(TAG, "bundledOnly,mainThreadDiskViolations=${violations.get()}")
                        finish()
                    }
                    return@launch
                }
                report("first", PruningTableProvider(cache).load())
                memory("afterFirst")
                report("warm", PruningTableProvider(cache).load())
                memory("afterWarm")
                // Story 3.8 production path: the bundled asset through a fresh provider (no memory reuse).
                val unused = File(cache.parentFile, "bundled-unused.bin")
                report(
                    "bundled",
                    PruningTableProvider(unused, generate = { error("bundled asset missing") }, bundled = {
                        assets.open(SolverModule.TABLE_ASSET)
                    }).load(),
                )
                memory("afterBundled")
                val cacheBytes = withContext(Dispatchers.IO) { cache.length() }
                mainHandler.post {
                    Log.i(TAG, "cacheBytes=$cacheBytes,mainThreadDiskViolations=${violations.get()}")
                    finish()
                }
            } catch (failure: Exception) {
                Log.e(TAG, "measurement-incomplete", failure)
                mainHandler.post { finish() }
            }
        }
    }

    /** The production bundled asset through a fresh provider (no in-memory reuse); never generates. */
    private suspend fun bundled(cache: File) {
        val unused = File(cache.parentFile, "bundled-unused.bin")
        val provider =
            PruningTableProvider(unused, generate = { error("bundled asset missing") }, bundled = {
                assets.open(SolverModule.TABLE_ASSET)
            })
        report("bundled", provider.load())
        memory("afterBundled")
    }

    /** Charging, battery level, thermal status and stay-awake evidence for the run record. */
    private fun conditions(): String {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val level = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val power = getSystemService(PowerManager::class.java)
        return "conditions,plugged=$plugged,batteryPercent=$level,thermalStatus=${power.currentThermalStatus}," +
            "interactive=${power.isInteractive},powerSave=${power.isPowerSaveMode}"
    }

    /** System available memory (ActivityManager and /proc/meminfo) and this process's PSS and heaps. */
    private suspend fun memory(stage: String) {
        val info = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val memAvailableKb =
            withContext(Dispatchers.IO) {
                File("/proc/meminfo").useLines { lines ->
                    lines.firstOrNull { it.startsWith("MemAvailable:") }?.filter(Char::isDigit)?.toLongOrNull() ?: -1
                }
            }
        val runtime = Runtime.getRuntime()
        Log.i(
            TAG,
            "memory,$stage,availMemBytes=${info.availMem},totalMemBytes=${info.totalMem},lowMemory=${info.lowMemory}," +
                "thresholdBytes=${info.threshold},procMemAvailableKb=$memAvailableKb,pssKb=${Debug.getPss()}," +
                "javaHeapUsedBytes=${runtime.totalMemory() - runtime.freeMemory()},javaHeapMaxBytes=${runtime.maxMemory()}," +
                "nativeHeapAllocatedBytes=${Debug.getNativeHeapAllocatedSize()}",
        )
    }

    private fun report(
        run: String,
        result: PruningTableLoadResult,
    ) {
        when (result) {
            is PruningTableLoadResult.Ready -> {
                Log.i(
                    TAG,
                    "$run,source=${result.source},tableBytes=${result.tables.byteSize}," +
                        "generationNanos=${result.generationNanos},cacheReadNanos=${result.cacheReadNanos}," +
                        "cacheWriteNanos=${result.cacheWriteNanos},moveTableNanos=${result.moveTableNanos}",
                )
            }

            is PruningTableLoadResult.Failed -> {
                Log.e(TAG, "$run,failed=${result.reason}")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        previousPolicy?.let(StrictMode::setThreadPolicy)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PruningTablesBenchmark"
    }
}
