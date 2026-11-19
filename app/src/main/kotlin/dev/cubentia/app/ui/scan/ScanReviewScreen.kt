package dev.cubentia.app.ui.scan

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import dev.cubentia.app.R
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalHaptics
import dev.cubentia.app.preferences.LocalRenderStyle
import dev.cubentia.app.render.Net2DRenderer
import dev.cubentia.app.ui.editor.FACE_NAMES
import dev.cubentia.app.ui.editor.FaceSelector
import dev.cubentia.app.ui.editor.ProblemTag
import dev.cubentia.app.ui.editor.StickerCue
import dev.cubentia.app.ui.editor.StickerProblemMark
import dev.cubentia.app.ui.editor.StickerReviewNet
import dev.cubentia.app.ui.editor.Swatch
import dev.cubentia.app.ui.editor.colorName
import dev.cubentia.app.ui.editor.currentWindowSizeClass
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.ReviewProblem
import dev.cubentia.domain.recognition.ScanReview
import kotlinx.coroutines.launch
import dev.cubentia.domain.text.Text as ContentText

private enum class DiscardAction { CLOSE, ENTER_COLORS }

/**
 * Renders immutable review decisions; all sticker edits are delegated to the domain value. While
 * [coachMarkPending] (the Learner's first review) and a sticker needs a color, a one-time coach mark
 * shows; [onCoachMarkDismissed] is called when the Learner taps to dismiss it, so the caller records it
 * as seen (PO 2026-11-16). It is hidden while no sticker needs a color, without counting as seen. A later review with blanks
 * shows it again until it is tapped. [edited] says the review was already changed before this screen
 * was shown (back from calibration), so Close and system Back still ask before discarding.
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
    edited: Boolean = false,
    onEnterColors: (() -> Unit)? = null,
) {
    var face by rememberSaveable { mutableIntStateOf((review.selected ?: 0) / 9) }
    LaunchedEffect(review.selected) { review.selected?.let { face = it / 9 } }
    val problems = review.problems
    val linkedIndices = problems.indices.filter { problems[it].piece.isNotEmpty() }
    // Opens on the problem led by the pre-selected sticker (the count line for the top suspect, #174), else the first
    // problem holding it, else the first linked one.
    var focusedIndex by rememberSaveable {
        mutableStateOf(
            review.selected?.let { selected ->
                linkedIndices.firstOrNull {
                    problems[it].reason.key == "msg.validation.wrong-color-counts" &&
                        problems[it].positions.firstOrNull() == selected
                }
                    ?: linkedIndices.firstOrNull { problems[it].positions.firstOrNull() == selected }
                    ?: linkedIndices.firstOrNull { selected in problems[it].positions }
            },
        )
    }
    val focused = focusedIndex?.takeIf { it in linkedIndices } ?: linkedIndices.firstOrNull()
    val problemNumbers = linkedIndices.withIndex().associate { (order, index) -> index to order + 1 }
    val marks =
        linkedIndices.flatMap { problems[it].piece }.distinct().associateWith { sticker ->
            StickerProblemMark(
                numbers = linkedIndices.filter { sticker in problems[it].piece }.map(problemNumbers::getValue),
                conflicts = linkedIndices.filter { sticker in problems[it].positions }.map(problemNumbers::getValue),
                focusedNumber = focused?.let(problemNumbers::getValue),
            )
        }
    var changed by rememberSaveable { mutableStateOf(edited) }
    var confirmCalibration by rememberSaveable { mutableStateOf(false) }
    var discardAction by rememberSaveable { mutableStateOf<DiscardAction?>(null) }
    BackHandler(enabled = changed && discardAction == null) { discardAction = DiscardAction.CLOSE }
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
    val netRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val compact = !windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    BoxWithConstraints(modifier.safeDrawingPadding().testTag("review:screen")) {
        val oneFace = compact || (maxWidth - Spacing.s4 * 2) / Net2DRenderer.NET_COLUMNS < Spacing.touchMin
        var netSize by remember { mutableStateOf(IntSize.Zero) }
        val viewportHeight = with(LocalDensity.current) { maxHeight.toPx() }
        val netModifier = Modifier.bringIntoViewRequester(netRequester).onSizeChanged { netSize = it }
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
            SuspectLine(review, strings)
            StickerReviewNet(
                selected = review.selected,
                invalid = review.outlined,
                onStickerClick = { onReview(review.select(it)) },
                editable = { it % 9 != 4 },
                face = if (oneFace) face else null,
                unresolved = review.stickers,
                strings = strings,
                cue = cue,
                problemMarks = marks,
                modifier =
                    if (oneFace) {
                        netModifier.widthIn(max = Spacing.touchMin * 6).fillMaxWidth().aspectRatio(1f)
                    } else {
                        netModifier.fillMaxWidth().aspectRatio(Net2DRenderer.NET_COLUMNS.toFloat() / Net2DRenderer.NET_ROWS)
                    },
            )
            if (coachVisible && needsFix) {
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
                            if (painted.stickers != review.stickers) {
                                changed = true
                                focusedIndex = null
                            }
                            cue = StickerCue(index, (cue?.serial ?: 0) + 1)
                            lastSet = index to color
                            if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                            onReview(painted)
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                problems.forEachIndexed { index, problem ->
                    ProblemLine(problem, problemNumbers[index], index == focused, strings, "review:problem:$index") {
                        val sticker = problem.positions.first()
                        focusedIndex = index
                        face = sticker / 9
                        onReview(review.select(sticker))
                        scope.launch {
                            val target =
                                if (netSize.height > viewportHeight) {
                                    // A short window can only reveal the selected row of the net.
                                    val row = if (oneFace) sticker % 9 / 3 else Net2DRenderer.netCell(sticker).second
                                    val cell = netSize.height.toFloat() / if (oneFace) 3 else Net2DRenderer.NET_ROWS
                                    Rect(0f, row * cell, netSize.width.toFloat(), (row + 1) * cell)
                                } else {
                                    null
                                }
                            netRequester.bringIntoView(target)
                        }
                    }
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
                onClick = { if (changed) discardAction = DiscardAction.CLOSE else onClose() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.inkPrimary),
                border = BorderStroke(1.dp, tokens.hairline),
                modifier = Modifier.fillMaxWidth().testTag("review:close"),
            ) {
                Text(strings.resolve(ContentText("btn.scan.close")))
            }
            if (onEnterColors != null) {
                OutlinedButton(
                    onClick = { if (changed) discardAction = DiscardAction.ENTER_COLORS else onEnterColors() },
                    modifier = Modifier.fillMaxWidth().testTag("review:enterColors"),
                ) {
                    Text(strings.resolve(ContentText("btn.scan.enter-colors-instead")))
                }
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
    val pendingDiscard = discardAction
    if (pendingDiscard != null) {
        AlertDialog(
            onDismissRequest = { discardAction = null },
            title = { Text(strings.resolve(ContentText("msg.scan.discard-title"))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardAction = null
                        when (pendingDiscard) {
                            DiscardAction.CLOSE -> onClose()
                            DiscardAction.ENTER_COLORS -> onEnterColors?.invoke()
                        }
                    },
                    modifier = Modifier.testTag("review:discard:confirm"),
                ) { Text(strings.resolve(ContentText("btn.scan.discard"))) }
            },
            dismissButton = {
                TextButton(onClick = { discardAction = null }, modifier = Modifier.testTag("review:discard:cancel")) {
                    Text(strings.resolve(ContentText("btn.scan.cancel")))
                }
            },
            modifier = Modifier.testTag("review:discard"),
        )
    }
}

@Composable
private fun ProblemLine(
    problem: ReviewProblem,
    number: Int?,
    focused: Boolean,
    strings: ContentStrings,
    tag: String,
    onSelect: () -> Unit,
) {
    val reason = strings.resolve(problem.reason)
    val color = RubikTheme.tokens.statusInvalid
    if (number == null) {
        Text(reason, color = color, modifier = Modifier.testTag(tag))
        return
    }
    val stickers = problem.positions.map { strings.resolve(ContentText("msg.scan.sticker-ref", stickerArgs(it))) }
    val args = mapOf("number" to number, "problem" to reason, "stickers" to stickers.joinToString("; "))
    val description =
        strings.resolve(
            if (problem.piece == problem.positions) {
                ContentText("msg.scan.problem-description-stickers", args)
            } else {
                val piece = problem.piece.map { strings.resolve(ContentText("msg.scan.sticker-ref", stickerArgs(it))) }
                ContentText("msg.scan.problem-description", args + ("piece" to piece.joinToString("; ")))
            },
        )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchMin)
                .clickable(
                    role = Role.Button,
                    onClickLabel = strings.resolve(ContentText("msg.scan.problem-action")),
                    onClick = onSelect,
                ).testTag(tag)
                .semantics {
                    contentDescription = description
                    selected = focused
                },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        ProblemTag(number, focused, "$tag:tag")
        Text(reason, color = color)
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

/** Suggests the likely color when the selected sticker is a suspect misread (#174), in a polite live region. */
@Composable
private fun SuspectLine(
    review: ScanReview,
    strings: ContentStrings,
) {
    val index = review.selected ?: return
    val suspect = review.suspects.firstOrNull { it.index == index } ?: return
    val color = stringResource(colorName(suspect.likely)).lowercase(LocalConfiguration.current.locales[0])
    Text(
        strings.resolve(ContentText("msg.scan.suspect-suggestion", mapOf("color" to color))),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().testTag("review:suggestion").semantics { liveRegion = LiveRegionMode.Polite },
    )
}
