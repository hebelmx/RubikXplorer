package dev.cubentia.app.ui.scan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import dev.cubentia.app.R
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalHaptics
import dev.cubentia.app.preferences.LocalRenderStyle
import dev.cubentia.app.render.Net2DRenderer
import dev.cubentia.app.ui.editor.FACE_NAMES
import dev.cubentia.app.ui.editor.FaceSelector
import dev.cubentia.app.ui.editor.StickerCue
import dev.cubentia.app.ui.editor.StickerReviewNet
import dev.cubentia.app.ui.editor.Swatch
import dev.cubentia.app.ui.editor.colorName
import dev.cubentia.app.ui.editor.currentWindowSizeClass
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.recognition.ScanReview
import java.util.Locale
import dev.cubentia.domain.text.Text as ContentText

/**
 * Renders immutable review decisions; all sticker edits are delegated to the domain value. While
 * [coachMarkPending] (the Learner's first review) and a sticker needs a color, a one-time coach mark
 * shows; [onCoachMarkDismissed] is called when the Learner taps to dismiss it, so the caller records it
 * as seen (PO 2026-11-16). A review closed without that tap shows it again next time.
 */
@Composable
fun ScanReviewScreen(
    review: ScanReview,
    strings: ContentStrings,
    onReview: (ScanReview) -> Unit,
    onAccept: (CubeState) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    windowSizeClass: WindowSizeClass = currentWindowSizeClass(),
    coachMarkPending: Boolean = false,
    onCoachMarkDismissed: () -> Unit = {},
    onCalibrate: (() -> Unit)? = null,
) {
    var face by rememberSaveable { mutableIntStateOf((review.selected ?: 0) / 9) }
    LaunchedEffect(review.selected) { review.selected?.let { face = it / 9 } }
    var changed by rememberSaveable { mutableStateOf(false) }
    var confirmCalibration by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var coachVisible by rememberSaveable { mutableStateOf(false) }
    val needsFix = review.blanks.isNotEmpty()
    LaunchedEffect(coachMarkPending && needsFix) {
        if (coachMarkPending && needsFix) coachVisible = true
    }
    var cue by remember { mutableStateOf<StickerCue?>(null) }
    var lastSet by remember { mutableStateOf<Pair<Int, Color>?>(null) }
    val hapticsEnabled = LocalHaptics.current
    val haptics = LocalHapticFeedback.current
    val tokens = RubikTheme.tokens
    val compact = !windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    BoxWithConstraints(modifier.safeDrawingPadding().testTag("review:screen")) {
        val oneFace = compact || (maxWidth - Spacing.s4 * 2) / Net2DRenderer.NET_COLUMNS < Spacing.touchMin
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(strings.resolve(ContentText("msg.scan.review-title")), style = MaterialTheme.typography.headlineSmall)
            Text(
                strings.resolve(instruction(review, strings, changed)),
                modifier = Modifier.fillMaxWidth().testTag("review:instruction"),
            )
            if (oneFace) {
                FaceSelector(face, (review.outlined + review.blanks).map { it / 9 }.toSet()) { shown ->
                    face = shown
                    // Keep the selection on the shown face: its first blank, else its first sticker.
                    onReview(review.select(review.blanks.firstOrNull { it / 9 == shown } ?: (shown * 9)))
                }
            }
            lastSet?.let { (index, color) -> SetStickerLine(index, color, strings) }
            SelectedStickerLine(review, strings)
            StickerReviewNet(
                selected = review.selected,
                invalid = review.outlined,
                onStickerClick = { onReview(review.select(it)) },
                editable = { it % 9 != 4 },
                face = if (oneFace) face else null,
                unresolved = review.stickers,
                strings = strings,
                cue = cue,
                modifier =
                    if (oneFace) {
                        Modifier.widthIn(max = Spacing.touchMin * 6).fillMaxWidth().aspectRatio(1f)
                    } else {
                        Modifier.fillMaxWidth().aspectRatio(Net2DRenderer.NET_COLUMNS.toFloat() / Net2DRenderer.NET_ROWS)
                    },
            )
            if (coachVisible) {
                CoachMark(strings) {
                    coachVisible = false
                    onCoachMarkDismissed()
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s2), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                listOf(Color.WHITE, Color.YELLOW, Color.RED, Color.ORANGE, Color.GREEN, Color.BLUE).forEach { color ->
                    Swatch(color, LocalRenderStyle.current) {
                        val index = review.selected
                        if (index != null && (!oneFace || index / 9 == face)) {
                            val painted = review.paint(color)
                            if (painted.stickers != review.stickers) changed = true
                            cue = StickerCue(index, (cue?.serial ?: 0) + 1)
                            lastSet = index to color
                            if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                            onReview(painted)
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                review.problems.forEachIndexed { index, problem ->
                    Text(
                        strings.resolve(problem.reason),
                        color = RubikTheme.tokens.statusInvalid,
                        modifier = Modifier.testTag("review:problem:$index"),
                    )
                }
            }
            if (onCalibrate != null) {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("review:calibrate")) {
                    Text(strings.resolve(ContentText("msg.calibration.offer")))
                    OutlinedButton({ confirmCalibration = true }, Modifier.testTag("review:calibrate:offer")) {
                        Text(strings.resolve(ContentText("btn.calibration.offer")))
                    }
                }
            }
            val accepted = review.accepted
            Button(
                onClick = { accepted?.let(onAccept) },
                enabled = accepted != null,
                modifier = Modifier.fillMaxWidth().testTag("review:accept"),
            ) {
                Text(strings.resolve(ContentText("btn.scan.accept")))
            }
            // Always enabled, in `ink-primary`: never greyed.
            OutlinedButton(
                onClick = { if (changed) confirmDiscard = true else onClose() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.inkPrimary),
                border = BorderStroke(1.dp, tokens.hairline),
                modifier = Modifier.fillMaxWidth().testTag("review:close"),
            ) {
                Text(strings.resolve(ContentText("btn.scan.close")))
            }
        }
    }
    if (confirmCalibration) {
        AlertDialog(
            onDismissRequest = { confirmCalibration = false },
            text = { Text(strings.resolve(ContentText("msg.calibration.confirm"))) },
            confirmButton = {
                TextButton({
                    confirmCalibration = false
                    onCalibrate?.invoke()
                }, Modifier.testTag("review:calibrate:confirm")) {
                    Text(strings.resolve(ContentText("btn.calibration.offer")))
                }
            },
            dismissButton = {
                TextButton({ confirmCalibration = false }, Modifier.testTag("review:calibrate:cancel")) {
                    Text(strings.resolve(ContentText("btn.scan.cancel")))
                }
            },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(strings.resolve(ContentText("msg.scan.discard-title"))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onClose()
                    },
                    modifier = Modifier.testTag("review:discard:confirm"),
                ) { Text(strings.resolve(ContentText("btn.scan.discard"))) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.testTag("review:discard:cancel")) {
                    Text(strings.resolve(ContentText("btn.scan.cancel")))
                }
            },
            modifier = Modifier.testTag("review:discard"),
        )
    }
}

/**
 * The instruction line follows the state: blanks left, else the first problem pointing at the
 * outlined piece, else valid: "All set" after the Learner [fixed] stickers, "Everything was read" when
 * the scan needed no fixes (PO 2026-11-16).
 */
private fun instruction(
    review: ScanReview,
    strings: ContentStrings,
    fixed: Boolean,
): ContentText {
    val blanks = review.blanks.size
    val problem = review.problems.firstOrNull()
    return when {
        blanks > 0 -> ContentText("msg.scan.review-hint-blanks", mapOf("count" to blanks))
        problem != null -> ContentText("msg.scan.review-hint-invalid", mapOf("problem" to strings.resolve(problem.reason)))
        fixed -> ContentText("msg.scan.review-hint-fixed")
        else -> ContentText("msg.scan.review-hint-ready")
    }
}

/** The one-time coach mark: points up at the flashing sticker and down at the palette; one tap dismisses it. */
@Composable
private fun CoachMark(
    strings: ContentStrings,
    onDismiss: () -> Unit,
) {
    val tokens = RubikTheme.tokens
    Surface(
        color = tokens.surfaceRaised,
        contentColor = tokens.inkPrimary,
        border = BorderStroke(2.dp, tokens.accent),
        shape = MaterialTheme.shapes.medium,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchMin)
                .clickable(role = Role.Button, onClick = onDismiss)
                .testTag("review:coach")
                .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.padding(Spacing.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
        ) {
            Text("↑", color = tokens.accent, modifier = Modifier.clearAndSetSemantics {})
            Text(strings.resolve(ContentText("msg.scan.coach-mark")), modifier = Modifier.weight(1f))
            Text("↓", color = tokens.accent, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/** "<Face> face, position <n>" with the face name capitalized at the start of the line. */
@Composable
private fun stickerArgs(index: Int): Map<String, Any> {
    val face = stringResource(FACE_NAMES[index / 9]).replaceFirstChar { it.titlecase(LocalConfiguration.current.locales[0]) }
    return mapOf("face" to face, "position" to (index % 9 + 1).toString())
}

/** Names the color just set ("Up face, position 4: orange") in a polite live region. */
@Composable
private fun SetStickerLine(
    index: Int,
    color: Color,
    strings: ContentStrings,
) {
    // Palette labels are capitalized; mid-sentence the color reads in lower case.
    val args = stickerArgs(index) + ("color" to stringResource(colorName(color)).lowercase(LocalConfiguration.current.locales[0]))
    Text(
        strings.resolve(ContentText("msg.scan.sticker-set", args)),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().testTag("review:updated").semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Names the selected sticker in a polite live region, so auto-advance is announced to TalkBack. */
@Composable
private fun SelectedStickerLine(
    review: ScanReview,
    strings: ContentStrings,
) {
    val index = review.selected ?: return
    val face = stringResource(FACE_NAMES[index / 9])
    val position = index % 9 + 1
    val color = review.stickers[index]
    val line =
        if (color == null) {
            strings.resolve(ContentText("msg.scan.sticker-needs-color", stickerArgs(index)))
        } else {
            val sticker = stringResource(R.string.editor_sticker, stringResource(colorName(color)), face, position)
            strings.resolve(ContentText("msg.scan.selected", mapOf("sticker" to sticker)))
        }
    Text(
        line,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().testTag("review:selected").semantics { liveRegion = LiveRegionMode.Polite },
    )
}
