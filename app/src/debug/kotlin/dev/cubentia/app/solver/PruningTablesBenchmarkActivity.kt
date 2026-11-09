package dev.cubentia.app.solver

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.util.Log
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
                report("first", PruningTableProvider(cache).load())
                report("warm", PruningTableProvider(cache).load())
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
                        "cacheWriteNanos=${result.cacheWriteNanos}",
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
