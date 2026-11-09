package dev.cubentia.macrobenchmark

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Runs outside the target process so am kill exercises a real cold persistence graph. */
@RunWith(AndroidJUnit4::class)
class LessonProcessDeathTest {
    @Test
    fun lessonCubeAndUndoSurviveProcessDeath() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(device.executeShellCommand("pm clear $PACKAGE").contains("Success"))
        launch(device)
        val firstLesson = checkNotNull(device.wait(Until.findObject(By.textContains("Tell centers, edges and corners apart")), TIMEOUT))
        firstLesson.click()
        val entry = stableFaces(device)
        tap(device, "R: right face clockwise")
        val afterR = stableFaces(device)
        tap(device, "U: up face clockwise")
        // The full 54-sticker reading (the cube's "Read faces" accessibility action) identifies the exact Cube State.
        val afterRU = stableFaces(device)
        assertNotEquals(entry, afterR)
        assertNotEquals(afterR, afterRU)
        Thread.sleep(1_000) // Let the asynchronous serial writer commit before moving the app to background.
        device.pressHome()
        device.waitForIdle()
        device.executeShellCommand("am kill $PACKAGE")
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (device.executeShellCommand("pidof $PACKAGE").trim().isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertEquals("", device.executeShellCommand("pidof $PACKAGE").trim())
        launch(device)
        checkNotNull(device.wait(Until.findObject(By.desc("R: right face clockwise")), TIMEOUT))
        assertEquals(afterRU, awaitFaces(device, afterRU))
        // Undo takes back exactly the last committed Move (U), so the restored history is the pre-death one.
        tap(device, "Undo")
        assertEquals(afterR, awaitFaces(device, afterR))
    }

    private fun launch(device: UiDevice) {
        device.executeShellCommand("monkey -p $PACKAGE -c android.intent.category.LAUNCHER 1")
        device.waitForIdle()
    }

    private fun tap(
        device: UiDevice,
        description: String,
    ) {
        val target = checkNotNull(device.wait(Until.findObject(By.desc(description)), TIMEOUT))
        assertTrue("$description is disabled", target.isEnabled)
        target.click()
        device.waitForIdle()
    }

    /** Reads the faces until two readings agree, so a face turn still animating is not captured. */
    private fun stableFaces(device: UiDevice): String {
        val deadline = System.currentTimeMillis() + TIMEOUT
        var last = readFaces(device)
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(SETTLE)
            val next = readFaces(device)
            if (next != null && next == last) return next
            last = next
        }
        error("cube faces never settled: $last")
    }

    /** Reads the faces until they equal [expected] or the timeout passes; returns the last reading. */
    private fun awaitFaces(
        device: UiDevice,
        expected: String,
    ): String? {
        val deadline = System.currentTimeMillis() + TIMEOUT
        var last = readFaces(device)
        while (last != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(SETTLE)
            last = readFaces(device)
        }
        return last
    }

    /** Runs the cube's "Read faces" custom accessibility action and returns the full per-face color reading. */
    private fun readFaces(device: UiDevice): String? {
        device.waitForIdle()
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        val labels = setOf(READ_FACES, READ_SUMMARY)
        val cube = find(root) { node -> node.actionList.any { it.label?.toString() in labels } } ?: return null
        val action = cube.actionList.first { it.label?.toString() in labels }
        // Already reading faces (the action now offers the summary): the description is the reading.
        if (action.label.toString() == READ_SUMMARY) return cube.contentDescription?.toString()
        if (!cube.performAction(action.id)) return null
        return device.wait(Until.findObject(By.desc(Pattern.compile("^Up face: .*", Pattern.DOTALL))), TIMEOUT)?.contentDescription
    }

    private fun find(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            val found = node.getChild(index)?.let { find(it, predicate) }
            if (found != null) return found
        }
        return null
    }

    private companion object {
        const val PACKAGE = "com.exxerpro.cubentia"
        const val TIMEOUT = 10_000L
        const val SETTLE = 500L
        const val READ_FACES = "Read faces"
        const val READ_SUMMARY = "Read summary"
    }
}
