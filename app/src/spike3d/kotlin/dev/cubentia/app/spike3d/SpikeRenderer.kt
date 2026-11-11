package dev.cubentia.app.spike3d

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.cubentia.app.render.CubeRenderer
import dev.cubentia.app.render.PresentationItem
import dev.cubentia.domain.cube.Facelets
import java.util.Locale

/**
 * OQ-3 spike, throwaway: what both prototypes share. The port is implemented once here on top of
 * [SpikeTurnDriver]; each engine only draws the [SpikeCubeModel] and calls [SpikeTurnDriver.onFrame]
 * once per frame. Logs frame timing per turn under [TAG] (never Moves or facelets).
 */
abstract class SpikeRenderer(
    val engineName: String,
) : CubeRenderer {
    protected val driver = SpikeTurnDriver(onTurnFinished = ::log)
    private var turns = 0
    private var views = 0

    override fun submit(items: List<PresentationItem>) = driver.submit(items)

    /** Queues [count] View Rotations (camera yaw only) after any queued turns. */
    fun submitViewRotations(count: Int) = driver.submitViewRotations(count)

    override fun renderedFacelets(): Facelets = driver.facelets()

    /** True while a turn or View Rotation is animating or queued. */
    fun isBusy(): Boolean = driver.isBusy()

    @Composable
    abstract fun Content(modifier: Modifier)

    private fun log(stats: TurnStats) {
        if (stats.viewRotation) {
            Log.i(
                TAG,
                String.format(
                    Locale.ROOT,
                    "view n=%d engine=%s elapsedMs=%.1f frames=%d p50=%.2f p90=%.2f p99=%.2f max=%.2f over33=%d readbackOk=%b",
                    ++views,
                    engineName,
                    stats.elapsedMillis,
                    stats.frames,
                    stats.p50,
                    stats.p90,
                    stats.p99,
                    stats.max,
                    stats.over33,
                    stats.readbackMatchesTarget,
                ),
            )
            return
        }
        Log.i(
            TAG,
            String.format(
                Locale.ROOT,
                "turn n=%d engine=%s fast=%b elapsedMs=%.1f frames=%d p50=%.2f p90=%.2f p99=%.2f max=%.2f over33=%d readbackOk=%b",
                ++turns,
                engineName,
                stats.fastForward,
                stats.elapsedMillis,
                stats.frames,
                stats.p50,
                stats.p90,
                stats.p99,
                stats.max,
                stats.over33,
                stats.readbackMatchesTarget,
            ),
        )
    }

    companion object {
        const val TAG = "Spike3d"
    }
}
