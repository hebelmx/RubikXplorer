package dev.rubikxplorer.macrobenchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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
        tap(device, "R: right face clockwise")
        tap(device, "U: up face clockwise")
        val cube = cubeDescription(device)
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
        assertEquals(cube, cubeDescription(device))
        assertTrue(checkNotNull(device.wait(Until.findObject(By.desc("Undo")), TIMEOUT)).isEnabled)
    }

    private fun launch(device: UiDevice) {
        device.executeShellCommand("monkey -p $PACKAGE -c android.intent.category.LAUNCHER 1")
        device.waitForIdle()
    }

    private fun tap(
        device: UiDevice,
        description: String,
    ) {
        checkNotNull(device.wait(Until.findObject(By.desc(description)), TIMEOUT)).click()
        device.waitForIdle()
    }

    private fun cubeDescription(device: UiDevice): String =
        checkNotNull(
            device.wait(
                Until.findObject(
                    By.desc(
                        java.util.regex.Pattern
                            .compile("(?:Pattern reached|[0-9]+ of [0-9]+ pieces match the pattern)"),
                    ),
                ),
                TIMEOUT,
            ),
        ).contentDescription

    private companion object {
        const val PACKAGE = "dev.rubikxplorer"
        const val TIMEOUT = 10_000L
    }
}
