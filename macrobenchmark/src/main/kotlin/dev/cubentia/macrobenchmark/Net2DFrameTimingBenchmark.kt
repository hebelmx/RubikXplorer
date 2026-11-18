package dev.cubentia.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * NFR-2 frame timing on the Solution Playback 2D net on the profileable `:app` benchmark build.
 * Setup solves a cube with two flipped edges or resumes restored playback. Each measured window
 * plays for 4.8 s at normal speed (about eight Moves; Play shows the first at once) and checks both
 * counter advancement and the UI frame count. Setup fails when animations are off (Reduce Motion
 * snaps the net instead of animating it).
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class Net2DFrameTimingBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun faceTurnsOnNet2D() {
        lateinit var before: Counter
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(MinFrameCountMetric(MIN_FRAMES), FrameTimingMetric()),
            compilationMode = CompilationMode.Full(),
            startupMode = StartupMode.WARM,
            iterations = ITERATIONS,
            setupBlock = {
                val scale = device.executeShellCommand("settings get global animator_duration_scale").trim()
                check(scale.toFloatOrNull() != 0f) {
                    "animator_duration_scale is $scale: Reduce Motion snaps the net, so playback would not animate it"
                }
                startActivityAndWait()
                openSolutionPlayback()
                requireNet()
                pausePlayback()
                var counter = playbackCounter()
                check(counter.size > 0) { "Solution Playback counter is $counter; need $PLAYED_MOVES remaining Moves" }
                device.requireObject(By.text("1×").enabled(true), "Solution Playback 1× speed").click()
                if (counter.remaining < PLAYED_MOVES) {
                    device.requireObject(By.desc("Restart").enabled(true), "Solution Playback Restart").click()
                    device.requireObject(By.desc("Pause"), "Solution Playback Pause after Restart")
                    pausePlayback()
                    counter = playbackCounter()
                }
                check(counter.remaining >= PLAYED_MOVES) {
                    "Solution Playback counter is $counter; need $PLAYED_MOVES remaining Moves"
                }
                before = counter
            },
        ) {
            device.requireObject(By.desc("Play").enabled(true), "Solution Playback Play").click()
            Thread.sleep(PLAYED_MOVES * STEP_MS)
            val after = playbackCounter()
            pausePlayback()
            check(after.position - before.position >= PLAYED_MOVES - 1) {
                "Solution Playback counter advanced from $before to $after; " +
                    "expected at least ${PLAYED_MOVES - 1} Moves"
            }
        }
    }

    private fun MacrobenchmarkScope.requireNet() = requirePlaybackView("net")

    private fun MacrobenchmarkScope.playbackCounter(): Counter {
        val text = device.requireObject(By.text(COUNTER), "Solution Playback Move counter").text.orEmpty()
        val (position, size) =
            checkNotNull(COUNTER.toRegex().matchEntire(text)) { "Invalid Solution Playback Move counter: '$text'" }.destructured
        return Counter(position.toInt(), size.toInt())
    }

    private data class Counter(
        val position: Int,
        val size: Int,
    ) {
        val remaining: Int get() = size - position

        override fun toString(): String = "$position / $size"
    }

    private companion object {
        const val TARGET_PACKAGE = "com.exxerpro.cubentia"
        val COUNTER: Pattern = Pattern.compile("""(\d+) / (\d+)""")
        const val ITERATIONS = 5
        const val PLAYED_MOVES = 8
        const val STEP_MS = 600L

        // At 30 Hz the 4.8 s window offers 144 vsyncs. Allow headroom while rejecting static-screen ripples.
        const val MIN_FRAMES = 100
    }
}
