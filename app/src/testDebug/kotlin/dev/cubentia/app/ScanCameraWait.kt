package dev.cubentia.app

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.robolectric.Shadows.shadowOf

/**
 * Waits until the scan camera is started. [running] is the caller's own condition for "the camera is started", usually
 * (not always) on its fake frame source.
 *
 * The camera starts from a recomposition that follows a state write made on the main looper after background hops
 * (calibration load, controller build). Under Robolectric that write is often never shown to Compose: `waitUntil` only
 * steps the test clock, so it neither runs the paused main looper nor applies pending snapshot writes, and the wait
 * hung for its full 10 s whenever the hops ended after the content had settled (GH #400). So every poll calls
 * `waitForIdle`, which does both: it drains the main looper and applies pending snapshot writes. The explicit looper
 * pump before it is only a safeguard.
 */
internal fun ComposeTestRule.awaitScanCamera(running: () -> Boolean) {
    waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        waitForIdle()
        running()
    }
}
