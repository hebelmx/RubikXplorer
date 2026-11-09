package dev.cubentia.app.spike3d

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cubentia.app.render.PresentationItem
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Turn
import kotlinx.coroutines.delay

/**
 * OQ-3 spike, throwaway launcher (spike builds only): pick an engine, turn R / R', or queue four
 * turns at once to see fast-forward. Frame timing per turn is logged under [SpikeRenderer.TAG].
 *
 * For unattended measurement: `adb shell am start -n com.exxerpro.cubentia/dev.cubentia.app.spike3d.Spike3dActivity
 * --es engine sceneview --ei turns 40 --ez burst true` runs 40 alternating R / R' turns, then
 * one burst of four, and logs `autorun done`.
 */
class Spike3dActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialEngine = intent.getStringExtra(EXTRA_ENGINE)?.takeIf { it in SpikeEngines.names } ?: SpikeEngines.names.first()
        val autorunTurns = intent.getIntExtra(EXTRA_TURNS, 0)
        val burst = intent.getBooleanExtra(EXTRA_BURST, false)
        setContent { MaterialTheme { Surface { SpikeScreen(initialEngine, autorunTurns, burst) } } }
    }

    private class Session {
        var state: CubeState = CubeState.SOLVED
        var revision = 0L

        fun animate(move: Move): PresentationItem {
            state = state.apply(move)
            return PresentationItem.Animate(move, ++revision, state.toFacelets())
        }
    }

    @Composable
    private fun SpikeScreen(
        initialEngine: String,
        autorunTurns: Int,
        burst: Boolean,
    ) {
        var engine by remember { mutableStateOf(initialEngine) }
        val session = remember(engine) { Session() }
        val renderer = remember(engine) { SpikeEngines.create(engine) }
        val r = FaceTurn(Face.R, Turn.CLOCKWISE)
        val rPrime = FaceTurn(Face.R, Turn.COUNTERCLOCKWISE)

        var autorunDone by rememberSaveable { mutableStateOf(autorunTurns <= 0) }

        // Once per activity: switching engines afterwards does not restart it.
        LaunchedEffect(renderer) {
            if (autorunDone) return@LaunchedEffect
            autorunDone = true
            delay(WARM_UP_MILLIS)
            repeat(autorunTurns) { i ->
                renderer.submit(listOf(session.animate(if (i % 2 == 0) r else rPrime)))
                delay(PAUSE_MILLIS)
            }
            if (burst) {
                renderer.submit(listOf(r, rPrime, r, rPrime).map(session::animate))
            }
            while (renderer.isBusy()) delay(PAUSE_MILLIS)
            Log.i(
                SpikeRenderer.TAG,
                "autorun done engine=${renderer.engineName} readbackOk=${renderer.renderedFacelets() == session.state.toFacelets()}",
            )
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpikeEngines.names.forEach { name ->
                    if (name == engine) Button(onClick = {}) { Text(name) } else OutlinedButton(onClick = { engine = name }) { Text(name) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { renderer.submit(listOf(session.animate(r))) }) { Text("R") }
                Button(onClick = { renderer.submit(listOf(session.animate(rPrime))) }) { Text("R'") }
                Button(onClick = { renderer.submit(listOf(r, rPrime, r, rPrime).map(session::animate)) }) { Text("×4 queued") }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                key(engine) { renderer.Content(Modifier.fillMaxSize()) }
            }
        }
    }

    private companion object {
        const val EXTRA_ENGINE = "engine"
        const val EXTRA_TURNS = "turns"
        const val EXTRA_BURST = "burst"
        const val WARM_UP_MILLIS = 2_000L
        const val PAUSE_MILLIS = 500L
    }
}
