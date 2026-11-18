package dev.cubentia.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal fun MacrobenchmarkScope.openSolutionPlayback() {
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

internal fun MacrobenchmarkScope.pausePlayback() {
    device.findObject(By.desc("Pause"))?.click()
    device.requireObject(By.desc("Play"), "paused Solution Playback Play")
}

internal fun UiDevice.requireObject(
    selector: BySelector,
    name: String,
    timeoutMs: Long = TIMEOUT_MS,
): UiObject2 = checkNotNull(wait(Until.findObject(selector), timeoutMs)) { "Missing $name" }

internal fun MacrobenchmarkScope.requirePlaybackView(tag: String) {
    val stage = By.res(tag)
    if (!device.hasObject(stage)) {
        device.requireObject(By.res("playback:viewSwitch"), "Solution Playback view switch").click()
    }
    device.requireObject(stage, "Solution Playback stage with resource ID $tag")
}

private const val SOLVE_TAB = "Solve"
private const val IM_NEW = "I'm new"
private val SOLVE_OR_WELCOME = Pattern.compile("${Pattern.quote(SOLVE_TAB)}|${Pattern.quote(IM_NEW)}")
private val PLAY_OR_PAUSE = Pattern.compile("Play|Pause")
private const val TIMEOUT_MS = 5_000L
private const val SOLVE_TIMEOUT_MS = 60_000L
