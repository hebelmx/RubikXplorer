package dev.cubentia.app.camera

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalHaptics
import dev.cubentia.app.preferences.LocalRenderStyle
import dev.cubentia.app.render.StickerGlyph
import dev.cubentia.app.ui.theme.DarkTokens
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.domain.appearance.StickerAppearance
import dev.cubentia.domain.recognition.continuous.FaceSlot
import kotlinx.coroutines.channels.ReceiveChannel
import dev.cubentia.domain.text.Text as ContentText

/** GH #409: the debug overlay's detector peaks (filled dots sized by score); distinct from the outline highlight. */
internal val DEBUG_PEAK_COLOR = Color(0xFF00E5FF)

/** GH #409: the debug overlay's lattice cells (inlier filled, no peak a ring). */
internal val DEBUG_LATTICE_COLOR = Color(0xFFFFEA00)

/** Below this height (the compact window height class, AD-17) the two-pane panel pairs *Review and Correct* with *Enter colors instead*. */
private val SHORT_PANEL_HEIGHT = WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND.dp

@Composable
internal fun ContinuousQuickScanScreen(
    state: ContinuousQuickScanFlow,
    strings: ContentStrings,
    actions: GuidedScanActions,
    torch: Boolean?,
    preview: @Composable () -> Unit,
    /**
     * Story 10.34: the debug Quick Scan session recording's controls (Rec, indicator, notice, sheet), drawn inside the
     * camera area; null in every non-debuggable build, so no Rec node exists there.
     */
    sessionOverlay: (@Composable BoxScope.() -> Unit)? = null,
    /** Story 10.34: false during the session recording's baseline run (Reset, Scan face by face, Review and Correct, Enter colors instead). */
    actionsEnabled: Boolean = true,
) {
    val highlight = RubikTheme.tokens.stickerHighlight
    val camera: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.testTag("scanViewfinder")) {
            preview()
            Canvas(Modifier.fillMaxSize()) {
                state.outlines.values.forEach { outline ->
                    val path = Path()
                    outline.corners.forEachIndexed { i, corner ->
                        val x = corner.x.toFloat() * size.width
                        val y = corner.y.toFloat() * size.height
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    drawPath(path, highlight, style = Stroke(2.dp.toPx()))
                }
                state.debugOverlay?.let { debug ->
                    // GH #409 (debug only): the fitted lattices, then the detector peaks over them.
                    val cell = 4.dp.toPx()
                    debug.lattices.forEach { lattice ->
                        lattice.centers.forEachIndexed { i, c ->
                            val at = Offset(c.x.toFloat() * size.width, c.y.toFloat() * size.height)
                            if (lattice.inliers.getOrElse(i) { false }) {
                                drawCircle(DEBUG_LATTICE_COLOR, cell, at)
                            } else {
                                drawCircle(DEBUG_LATTICE_COLOR, cell, at, style = Stroke(1.5.dp.toPx()))
                            }
                        }
                    }
                    debug.peaks.forEach { peak ->
                        val radius = (1.5f + 2.5f * peak.score.coerceIn(0f, 1f)).dp.toPx()
                        drawCircle(DEBUG_PEAK_COLOR, radius, Offset(peak.viewX.toFloat() * size.width, peak.viewY.toFloat() * size.height))
                    }
                }
            }
            state.debugOverlay?.let { debug ->
                // Numbers and fixed words only (AD-13); above the session recording's Rec control in the same corner.
                Text(
                    debug.counts,
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 4.dp, bottom = 56.dp)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 4.dp)
                        .testTag("quickScan:debugCounts"),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            OutlinedButton(
                actions.onClose,
                Modifier
                    .align(
                        Alignment.TopStart,
                    ).padding(4.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                    .testTag("scanClose"),
            ) {
                Text(strings.resolve(ContentText("btn.scan.close")))
            }
            if (torch !=
                null
            ) {
                TorchButton(TorchControl(torch, strings, actions.onToggleTorch), Modifier.align(Alignment.TopEnd).padding(4.dp))
            }
            sessionOverlay?.invoke(this)
        }
    }
    val panel: @Composable (Modifier, Boolean) -> Unit = { modifier, short ->
        Column(modifier.padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ContinuousNet(state, Modifier.weight(1f).fillMaxWidth())
            Text(strings.resolve(ContinuousQuickScanFlow.countText(state.count)), Modifier.testTag("quickScan:count"))
            // One node for the whole scan: TalkBack speaks a live region when a node it knows changes, and Compose sends
            // no such event for a node that was just added. Described only while it is the count shown: after the count
            // fell, a swipe never reads the old number.
            val label =
                state.announcement?.takeIf { it.count == state.count }?.let {
                    strings.resolve(
                        ContinuousQuickScanFlow.countText(it.count),
                    )
                }
            Spacer(
                Modifier
                    .size(1.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        if (label != null) contentDescription = label
                    }.testTag("quickScan:announcement"),
            )
            Text(strings.resolve(state.cue.toText()), Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("quickScan:cue"))
            val reset = strings.resolve(ContentText("btn.quick-scan.reset"))
            if (state.snapshot?.twistDetected == true) {
                Button(actions.onReset, Modifier.fillMaxWidth().testTag("quickScan:reset"), enabled = actionsEnabled) { Text(reset) }
            } else {
                OutlinedButton(
                    actions.onReset,
                    Modifier.fillMaxWidth().testTag("quickScan:reset"),
                    enabled = actionsEnabled,
                ) { Text(reset) }
            }
            OutlinedButton(actions.onFaceByFace, Modifier.fillMaxWidth().testTag("quickScan:faceByFace"), enabled = actionsEnabled) {
                Text(strings.resolve(ContentText("btn.quick-scan.face-by-face")))
            }
            // *Review and Correct* (Q8): shown or gone at once, with no animation or announcement; never the emphasised
            // action. Hidden, it still takes its place (not drawn, not tappable, and no node for TalkBack), so no other
            // action moves under a finger when it appears or goes. On a short panel (a phone in landscape) it shares
            // the last row, so the net keeps a readable cell.
            val offered = state.reviewAndCorrectOffered() && actionsEnabled
            val reviewAndCorrect: @Composable (Modifier) -> Unit = { place ->
                OutlinedButton(
                    actions.onReviewAndCorrect,
                    if (offered) place.testTag("quickScan:reviewAndCorrect") else place.alpha(0f).clearAndSetSemantics { },
                    enabled = offered,
                ) {
                    Text(strings.resolve(ContentText("btn.quick-scan.review-and-correct")))
                }
            }
            if (short) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The minimum touch height, which *Enter colors instead* beside it sets for itself too.
                    reviewAndCorrect(Modifier.weight(1f).heightIn(min = 48.dp))
                    Box(Modifier.weight(1f)) { EnterColorsButton(strings, actions.onEnterColors, enabled = actionsEnabled) }
                }
            } else {
                reviewAndCorrect(Modifier.fillMaxWidth())
                EnterColorsButton(strings, actions.onEnterColors, enabled = actionsEnabled)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("quickScan:continuous")) {
        // AD-17: the same width-class rule as the Guided viewfinder this screen hands over to.
        if (scanTwoPane(maxWidth, maxHeight)) {
            Row(Modifier.fillMaxSize()) {
                camera(Modifier.weight(1f).fillMaxHeight())
                panel(Modifier.weight(1f).fillMaxHeight(), this@BoxWithConstraints.maxHeight < SHORT_PANEL_HEIGHT)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                camera(Modifier.fillMaxWidth().height(this@BoxWithConstraints.maxHeight * 0.34f))
                panel(Modifier.weight(1f).fillMaxWidth(), false)
            }
        }
    }
}

/**
 * The haptic of a Quick Scan [pulse] on Android [sdk]: a light tick, a confirm and a reject. `Confirm` and `Reject` do
 * nothing below API 30 (the app's minSdk is 29), where a long-press pulse stands in for both.
 */
internal fun hapticFor(
    pulse: ContinuousQuickScanFlow.Pulse,
    sdk: Int = Build.VERSION.SDK_INT,
): HapticFeedbackType =
    when (pulse) {
        ContinuousQuickScanFlow.Pulse.Tick -> {
            HapticFeedbackType.KeyboardTap
        }

        ContinuousQuickScanFlow.Pulse.Confirm -> {
            if (sdk >=
                Build.VERSION_CODES.R
            ) {
                HapticFeedbackType.Confirm
            } else {
                HapticFeedbackType.LongPress
            }
        }

        ContinuousQuickScanFlow.Pulse.Reject -> {
            if (sdk >=
                Build.VERSION_CODES.R
            ) {
                HapticFeedbackType.Reject
            } else {
                HapticFeedbackType.LongPress
            }
        }
    }

/** Plays each of [pulses] once, and only while the app's haptics setting ([LocalHaptics]) is on. */
@Composable
internal fun ContinuousQuickScanHaptics(
    pulses: ReceiveChannel<ContinuousQuickScanFlow.Pulse>,
    sdk: Int = Build.VERSION.SDK_INT,
) {
    val feedback = LocalHapticFeedback.current
    val enabled by rememberUpdatedState(LocalHaptics.current)
    LaunchedEffect(pulses, feedback) {
        for (pulse in pulses) {
            if (enabled) feedback.performHapticFeedback(hapticFor(pulse, sdk))
        }
    }
}

@Composable
private fun ContinuousNet(
    state: ContinuousQuickScanFlow,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.testTag("quickScan:net").clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        val cell = minOf(maxWidth / 12, maxHeight / 9, 24.dp)
        val glyphs = LocalRenderStyle.current.colorBlindGlyphs
        Box(Modifier.size(cell * 12, cell * 9)) {
            for ((face, position) in NET_POSITION) {
                for (index in 0 until 9) {
                    val color =
                        state.snapshot
                            ?.net
                            ?.get(face)
                            ?.get(index)
                    val appearance = color?.let(StickerAppearance::of)
                    val shape = RoundedCornerShape(2.dp)
                    val conflict = FaceSlot(face, index) in state.snapshot?.conflictedSlots.orEmpty()
                    Box(
                        Modifier
                            .offset(cell * (position.first * 3 + index % 3), cell * (position.second * 3 + index / 3))
                            .size(cell)
                            .padding(0.5.dp)
                            .background(appearance?.let { Color(it.fill) } ?: DarkTokens.surfaceStage, shape)
                            .then(if (conflict) Modifier.border(2.dp, RubikTheme.tokens.stickerHighlight, shape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (appearance != null && glyphs) StickerGlyph(appearance, cell)
                    }
                }
            }
        }
    }
}
