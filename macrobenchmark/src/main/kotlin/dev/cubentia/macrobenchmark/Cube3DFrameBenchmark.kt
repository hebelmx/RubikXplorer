package dev.cubentia.macrobenchmark

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * NFR-1/NFR-2 on the production 3D renderer (Story 12.2): GL frame rate and input-to-frame latency of
 * `Cube3DRenderer` on the Solve tab's free cube, read from the app's trace sections by
 * [GlFrameMetric], on the `:app` `benchmark` build type (release code, R8, profileable).
 *
 * - [faceTurns]: taps R U R' U' on the Move key bar, [TAP_GAP_MS] apart so each 200 ms turn completes.
 * - [viewRotation]: drags across the stage from a point off the cube (View Rotation), [SWIPES] swipes
 *   of about [SWIPE_MS] alternating direction.
 *
 * Run on demand on one device (the module's `LessonProcessDeathTest` runs `pm clear`):
 * `ANDROID_SERIAL=<serial> ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
 *   -Pandroid.testInstrumentationRunnerArguments.class=dev.cubentia.macrobenchmark.Cube3DFrameBenchmark`
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class Cube3DFrameBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun faceTurns() {
        logDisplay("faceTurns")
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(GlFrameMetric(GlFrameMetric.Mode.FACE_TURNS, FACE_KEYS.size)),
            compilationMode = CompilationMode.Full(),
            startupMode = StartupMode.WARM,
            iterations = ITERATIONS,
            setupBlock = {
                startActivityAndWait()
                openSolveTab()
            },
        ) {
            FACE_KEYS.forEach { description ->
                val key = checkNotNull(device.wait(Until.findObject(By.desc(description)), TIMEOUT_MS)) { "No key \"$description\"" }
                key.click()
                Thread.sleep(TAP_GAP_MS)
            }
        }
    }

    @Test
    fun viewRotation() {
        logDisplay("viewRotation")
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(GlFrameMetric(GlFrameMetric.Mode.VIEW_ROTATION, SWIPES)),
            compilationMode = CompilationMode.Full(),
            startupMode = StartupMode.WARM,
            iterations = ITERATIONS,
            setupBlock = {
                startActivityAndWait()
                openSolveTab()
            },
        ) {
            val bounds = cube().visibleBounds
            // A row near the stage's top edge, clear of the centered cube, so the drag rotates the view.
            val y = bounds.top + bounds.height() * OFF_CUBE_ROW
            val left = bounds.left + bounds.width() * EDGE
            val right = bounds.right - bounds.width() * EDGE
            repeat(SWIPES) { index ->
                val (from, to) = if (index % 2 == 0) left to right else right to left
                drag(from, to, y)
                Thread.sleep(SWIPE_GAP_MS)
            }
        }
    }

    /**
     * One horizontal drag of [SWIPE_MS] at y, with a MOVE every [STEP_MS] (a 125 Hz touch stream),
     * injected without waiting for each event. `UiDevice.swipe` injects synchronously and on the
     * S24 FE delivered a MOVE only every ~33 ms, which capped the View Rotation at 30 FPS by input
     * rate rather than by the renderer.
     */
    private fun drag(
        fromX: Float,
        toX: Float,
        y: Float,
    ) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = SystemClock.uptimeMillis()

        fun inject(
            action: Int,
            x: Float,
        ) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(event, false)) { "Could not inject drag event $action" }
            event.recycle()
        }
        inject(MotionEvent.ACTION_DOWN, fromX)
        val steps = SWIPE_MS / STEP_MS
        for (step in 1..steps) {
            Thread.sleep(STEP_MS.toLong())
            inject(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * step / steps)
        }
        inject(MotionEvent.ACTION_UP, toX)
    }

    /** A fresh install opens on the Welcome screen, which has no tabs; *I'm new* leads to them. */
    private fun MacrobenchmarkScope.openSolveTab() {
        val first = checkNotNull(device.wait(Until.findObject(By.text(SOLVE_OR_WELCOME)), TIMEOUT_MS)) { "No \"$SOLVE_TAB\" tab" }
        if (first.text == IM_NEW) first.click()
        val solve = checkNotNull(device.wait(Until.findObject(By.text(SOLVE_TAB)), TIMEOUT_MS)) { "No \"$SOLVE_TAB\" tab" }
        solve.click()
        checkNotNull(device.wait(Until.findObject(By.desc(FACE_KEYS.first())), TIMEOUT_MS)) { "Solve tab shows no Move keys" }
        cube()
    }

    private fun MacrobenchmarkScope.cube(): UiObject2 =
        checkNotNull(device.wait(Until.findObject(By.desc(CUBE_DESCRIPTION)), TIMEOUT_MS)) { "Solve tab shows no cube" }

    /** Run conditions for the evidence: the display's refresh rate and mode, logged under [TAG]. */
    private fun logDisplay(test: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val output =
            automation.executeShellCommand("dumpsys display").use { descriptor ->
                android.os.ParcelFileDescriptor
                    .AutoCloseInputStream(descriptor)
                    .bufferedReader()
                    .readText()
            }
        val context = InstrumentationRegistry.getInstrumentation().context
        val rate =
            context
                .getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
                .refreshRate
        Log.i(TAG, "$test display.refreshRate=$rate")
        output
            .lineSequence()
            .filter { line -> DISPLAY_KEYS.any { it in line } }
            .take(MAX_DISPLAY_LINES)
            .forEach { Log.i(TAG, "$test dumpsys ${it.trim().take(MAX_LINE)}") }
    }

    private companion object {
        const val TAG = "Cube3DFrameBenchmark"
        const val TARGET_PACKAGE = "com.exxerpro.cubentia"
        const val SOLVE_TAB = "Solve"
        const val IM_NEW = "I'm new"
        val SOLVE_OR_WELCOME: Pattern = Pattern.compile("${Pattern.quote(SOLVE_TAB)}|${Pattern.quote(IM_NEW)}")

        /** `cube_description`: "Cube, <color> on top, <color> in front". */
        val CUBE_DESCRIPTION: Pattern = Pattern.compile("Cube, .* on top, .* in front")
        const val ITERATIONS = 10
        const val TIMEOUT_MS = 5_000L
        const val TAP_GAP_MS = 450L
        const val SWIPES = 4
        const val SWIPE_MS = 600
        const val STEP_MS = 8
        const val SWIPE_GAP_MS = 400L
        const val OFF_CUBE_ROW = 0.06f
        const val EDGE = 0.1f
        const val MAX_DISPLAY_LINES = 12
        const val MAX_LINE = 400
        val DISPLAY_KEYS = listOf("renderFrameRate", "mActiveSfDisplayMode", "mActiveModeId", "mRefreshRateSetting", "refreshRate")

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
