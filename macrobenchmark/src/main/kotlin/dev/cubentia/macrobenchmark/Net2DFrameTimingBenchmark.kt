package dev.cubentia.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NFR-2 frame timing of the 2D net only: the Solve tab's free cube (`Net2DRenderer`, Compose) while
 * face-turn keys are tapped, on the `:app` `benchmark` build type (release code, R8, profileable).
 *
 * Out of scope: the 3D renderer draws on a `GLSurfaceView`, whose frames `FrameTimingMetric` cannot
 * see; GL timing uses the in-app `onDrawFrame` interval log. Per-device baselines on the reference
 * devices are Story 1.11. Run on demand with a device or emulator:
 * `./gradlew :macrobenchmark:connectedBenchmarkAndroidTest` (see `docs/benchmarks.md`).
 */
@RunWith(AndroidJUnit4::class)
class Net2DFrameTimingBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun faceTurnsOnNet2D() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Full(),
            startupMode = StartupMode.WARM,
            iterations = ITERATIONS,
            setupBlock = {
                startActivityAndWait()
                openSolveTab()
            },
        ) {
            FACE_KEYS.forEach { description -> tapKey(description) }
        }

    private fun MacrobenchmarkScope.openSolveTab() {
        val solve = checkNotNull(device.wait(Until.findObject(By.text(SOLVE_TAB)), TIMEOUT_MS)) { "No \"$SOLVE_TAB\" tab" }
        solve.click()
        checkNotNull(device.wait(Until.findObject(By.desc(FACE_KEYS.first())), TIMEOUT_MS)) { "Solve tab shows no Move keys" }
    }

    /** Face keys expose only their plain Move name (content description); the label has no semantics. */
    private fun MacrobenchmarkScope.tapKey(description: String) {
        val key = checkNotNull(device.wait(Until.findObject(By.desc(description)), TIMEOUT_MS)) { "No key \"$description\"" }
        key.click()
        device.waitForIdle()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.exxerpro.cubentia"
        const val SOLVE_TAB = "Solve"
        const val ITERATIONS = 5
        const val TIMEOUT_MS = 5_000L

        /** R U R' U' by plain name (`movePlainName` in `:app`). */
        val FACE_KEYS =
            listOf(
                "R: right face clockwise",
                "U: up face clockwise",
                "R prime: right face counter-clockwise",
                "U prime: up face counter-clockwise",
            )
    }
}
