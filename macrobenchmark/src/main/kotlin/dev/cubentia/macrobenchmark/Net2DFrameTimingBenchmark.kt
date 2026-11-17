package dev.cubentia.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
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

    private fun MacrobenchmarkScope.openSolutionPlayback() {
        val first =
            checkNotNull(
                device.wait(
                    Condition<UiDevice, UiObject2?> { ui ->
                        ui.findObject(By.desc(PLAY_OR_PAUSE)) ?: ui.findObject(By.text(SOLVE_OR_WELCOME))
                    },
                    TIMEOUT_MS,
                ),
            ) {
                "No Solution Playback transport, Welcome button, or Solve tab after launch " +
                    "(a restored physical-mode playback has no transport: switch it to virtual first)"
            }
        if (first.contentDescription == "Play" || first.contentDescription == "Pause") return
        if (first.text == IM_NEW) first.click()
        device.requireObject(By.text(SOLVE_TAB), "Solve tab").click()
        device.requireObject(By.text("Enter colors"), "Solve tab Enter colors button").click()

        device.requireObject(By.desc("White sticker, up face, position 8"), "Manual Cube Editor up-face sticker")
        paintSticker("Up", "White", 8, "Green")
        paintSticker("Up", "White", 2, "Blue")
        paintSticker("Front", "Green", 2, "White")
        paintSticker("Back", "Blue", 2, "White")
        editorObject(By.text("Accept").enabled(true), "enabled Accept button").click()
        device.requireObject(By.desc("Play"), "Solution Playback Play after solving the edited cube", SOLVE_TIMEOUT_MS)
    }

    private fun MacrobenchmarkScope.paintSticker(
        face: String,
        originalColor: String,
        position: Int,
        newColor: String,
    ) {
        val upTab = By.desc("Up face")
        val onePerFace = device.hasObject(upTab)
        if (onePerFace) editorObject(By.desc("$face face"), "$face face tab").click()

        val sticker = "$originalColor sticker, ${face.lowercase()} face, position $position"
        editorObject(By.desc(sticker), sticker).click()
        val swatch = By.desc(newColor)
        val visibleSwatch = device.findObject(swatch)
        if (visibleSwatch != null) {
            visibleSwatch.click()
        } else {
            editorObject(swatch, "$newColor swatch").click()
            val top = if (onePerFace) upTab else By.desc("White sticker, up face, position 1")
            editorObject(top, "top of the sticker editor", Direction.UP)
        }
    }

    private fun MacrobenchmarkScope.editorObject(
        selector: BySelector,
        name: String,
        direction: Direction = Direction.DOWN,
    ): UiObject2 =
        checkNotNull(
            device.findObject(selector) ?: device
                .requireObject(By.scrollable(true), "Manual Cube Editor scroll area for $name")
                .scrollUntil(direction, Until.findObject(selector))
                ?: device.wait(Until.findObject(selector), TIMEOUT_MS),
        ) { "Manual Cube Editor shows no $name" }

    private fun MacrobenchmarkScope.requireNet() {
        val net = By.res("net")
        if (!device.hasObject(net)) {
            val toggle = device.findObject(By.desc(NET_TOGGLE)) ?: device.findObject(By.text(NET_TOGGLE))
            toggle?.click()
        }
        checkNotNull(device.wait(Until.findObject(net), TIMEOUT_MS)) { "Solution Playback shows no 2D net (resource ID net)" }
    }

    private fun MacrobenchmarkScope.pausePlayback() {
        device.findObject(By.desc("Pause"))?.click()
        device.requireObject(By.desc("Play"), "paused Solution Playback Play")
    }

    private fun MacrobenchmarkScope.playbackCounter(): Counter {
        val text = device.requireObject(By.text(COUNTER), "Solution Playback Move counter").text.orEmpty()
        val (position, size) =
            checkNotNull(COUNTER.toRegex().matchEntire(text)) { "Invalid Solution Playback Move counter: '$text'" }.destructured
        return Counter(position.toInt(), size.toInt())
    }

    private fun UiDevice.requireObject(
        selector: BySelector,
        name: String,
        timeoutMs: Long = TIMEOUT_MS,
    ): UiObject2 = checkNotNull(wait(Until.findObject(selector), timeoutMs)) { "Missing $name" }

    private data class Counter(
        val position: Int,
        val size: Int,
    ) {
        val remaining: Int get() = size - position

        override fun toString(): String = "$position / $size"
    }

    private companion object {
        const val TARGET_PACKAGE = "com.exxerpro.cubentia"
        const val SOLVE_TAB = "Solve"
        const val IM_NEW = "I'm new"
        val SOLVE_OR_WELCOME: Pattern = Pattern.compile("${Pattern.quote(SOLVE_TAB)}|${Pattern.quote(IM_NEW)}")
        val PLAY_OR_PAUSE: Pattern = Pattern.compile("Play|Pause")
        val NET_TOGGLE: Pattern = Pattern.compile(".*(?:2D|Net).*", Pattern.CASE_INSENSITIVE)
        val COUNTER: Pattern = Pattern.compile("""(\d+) / (\d+)""")
        const val ITERATIONS = 5
        const val TIMEOUT_MS = 5_000L
        const val SOLVE_TIMEOUT_MS = 60_000L
        const val PLAYED_MOVES = 8
        const val STEP_MS = 600L

        // At 30 Hz the 4.8 s window offers 144 vsyncs. Allow headroom while rejecting static-screen ripples.
        const val MIN_FRAMES = 100
    }
}
