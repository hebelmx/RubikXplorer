package dev.cubentia.app.ui.scan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.cubentia.app.ui.editor.PaintAnnouncer
import dev.cubentia.app.ui.editor.PaintInstruction
import dev.cubentia.app.ui.editor.PaintMode
import dev.cubentia.app.ui.editor.PaintPalette
import dev.cubentia.app.ui.editor.ProblemTag
import dev.cubentia.app.ui.editor.StickerCue
import dev.cubentia.app.ui.editor.StickerProblemMark
import dev.cubentia.app.ui.editor.StickerReviewNet
import dev.cubentia.app.ui.editor.colorName
import dev.cubentia.app.ui.editor.currentWindowSizeClass
import dev.cubentia.app.ui.editor.rememberPaintMode
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
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
 *
 * GH #321: *Close* with no change calls [onClose]; after a change it asks "Discard this scan?" and *Discard* (also for
 * system Back) calls [onDiscard]. With [enterColorsAsks] false, *Enter colors instead* never asks: it keeps the review's
 * fixes for the caller's Editor, so nothing is discarded.
 *
 * Story 10.32 (*Review and Correct*): with [onBack], system Back in review mode calls it at once, changed or not and
 * with no question, and *Close* always asks "Discard this scan?" ([onClose] is then never called). An open dialog and
 * retake mode keep their own Back. Without [onBack] nothing changes.
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
    onRetake: ((Set<Face>) -> Unit)? = null,
    onDiscard: () -> Unit = onClose,
    enterColorsAsks: Boolean = true,
    onBack: (() -> Unit)? = null,
) {
    // Retake mode (Story 10.16): the net selects whole faces to scan again; every other control is hidden.
    var retaking by rememberSaveable { mutableStateOf(false) }
    var retakeFaces by rememberSaveable { mutableStateOf(emptySet<Int>()) }
    // After *Retake N faces* the screen stays in retake mode, inert, until the Activity leaves the review (its phase hop
    // is asynchronous), so the review controls never flash; a changed review clears it.
    var launching by rememberSaveable(review) { mutableStateOf(false) }
    // Paint mode (#249) lives here, not in ReviewMode, so retake mode can end it and still announce "Painting off".
    val paint = rememberPaintMode()
    // The review paints its own `surface-base` and `ink-primary`: its host sets neither, so text would sit on the window (#261).
    val tokens = RubikTheme.tokens
    Surface(modifier = modifier.fillMaxSize(), color = tokens.surfaceBase, contentColor = tokens.inkPrimary) {
        Box {
            if (retaking && onRetake != null) {
                RetakeMode(
                    review = review,
                    strings = strings,
                    selectedFaces = retakeFaces,
                    launching = launching,
                    onToggle = { shown -> retakeFaces = if (shown in retakeFaces) retakeFaces - shown else retakeFaces + shown },
                    onStart = {
                        launching = true
                        onRetake(retakeFaces.map { Face.entries[it] }.toSet())
                    },
                    onCancel = {
                        retaking = false
                        retakeFaces = emptySet()
                    },
                    modifier = Modifier,
                )
            } else {
                ReviewMode(
                    review = review,
                    strings = strings,
                    onReview = onReview,
                    onAccept = onAccept,
                    onClose = onClose,
                    onDiscard = onDiscard,
                    enterColorsAsks = enterColorsAsks,
                    onBack = onBack,
                    modifier = Modifier,
                    windowSizeClass = windowSizeClass,
                    coachMarkPending = coachMarkPending,
                    onCoachMarkDismissed = onCoachMarkDismissed,
                    onCalibrate = onCalibrate,
                    edited = edited,
                    onEnterColors = onEnterColors,
                    paint = paint,
                    // Entering retake mode ends paint mode first.
                    onRetakeFaces =
                        onRetake?.let {
                            {
                                paint.stop()
                                retaking = true
                            }
                        },
                )
            }
            PaintAnnouncer(paint, strings)
        }
    }
}

/**
 * Retake mode (Story 10.16, PO 2026-11-19): the title, the full 6-face net (also on a compact phone) whose faces toggle
 * for retake, the instruction line, *Retake N faces* (enabled with at least one face) and *Cancel*. System Back is
 * *Cancel*: it leaves retake mode with the selection cleared and the review unchanged.
 */
@Composable
private fun RetakeMode(
    review: ScanReview,
    strings: ContentStrings,
    selectedFaces: Set<Int>,
    launching: Boolean,
    onToggle: (Int) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    BackHandler { if (!launching) onCancel() }
    Column(
        modifier
            .safeDrawingPadding()
            .testTag("review:screen")
            .verticalScroll(rememberScrollState())
            .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(strings.resolve(ContentText("msg.scan.review-title")), style = MaterialTheme.typography.headlineSmall)
        StickerReviewNet(
            selected = null,
            invalid = emptySet(),
            onStickerClick = {},
            editable = { false },
            unresolved = review.stickers,
            strings = strings,
            retakeSelected = selectedFaces,
            onFaceClick = { if (!launching) onToggle(it) },
            modifier = Modifier.fillMaxWidth().aspectRatio(Net2DRenderer.NET_COLUMNS.toFloat() / Net2DRenderer.NET_ROWS),
        )
        Text(
            strings.resolve(ContentText("msg.scan.retake-hint")),
            modifier = Modifier.fillMaxWidth().testTag("review:instruction"),
        )
        Button(
            onClick = onStart,
            enabled = selectedFaces.isNotEmpty() && !launching,
            modifier = Modifier.fillMaxWidth().testTag("review:retake:start"),
        ) {
            Text(strings.resolve(ContentText("btn.scan.retake-count", mapOf("count" to selectedFaces.size))))
        }
        OutlinedButton(
            onClick = onCancel,
            enabled = !launching,
            modifier = Modifier.fillMaxWidth().testTag("review:retake:cancel"),
        ) {
            Text(strings.resolve(ContentText("btn.scan.cancel")))
        }
    }
}

@Composable
private fun ReviewMode(
    review: ScanReview,
    strings: ContentStrings,
    onReview: (ScanReview) -> Unit,
    onAccept: (CubeState) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
    windowSizeClass: WindowSizeClass,
    coachMarkPending: Boolean,
    onCoachMarkDismissed: () -> Unit,
    onCalibrate: (() -> Unit)?,
    edited: Boolean,
    onEnterColors: (() -> Unit)?,
    paint: PaintMode,
    onDiscard: () -> Unit,
    enterColorsAsks: Boolean,
    onBack: (() -> Unit)?,
    onRetakeFaces: (() -> Unit)?,
) {
    val painting = paint.locked
    var face by rememberSaveable { mutableIntStateOf((review.selected ?: 0) / 9) }
    // In paint mode the selection is paused, so a paint that moves it on never switches the shown face.
    LaunchedEffect(review.selected, painting == null) {
        if (painting == null) review.selected?.let { face = it / 9 }
    }
    val problems = review.problems
    val linkedIndices = problems.indices.filter { problems[it].piece.isNotEmpty() }
    // Opens on the problem led by the pre-selected sticker (the count line for the top suspect, #174), else the first
    // problem holding it, else the first linked one.
    var focusedIndex by rememberSaveable {
        mutableStateOf(
            review.selected?.let { selected ->
                countsLineLedBy(problems, selected)
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
    // With [onBack] (Story 10.32) Back leaves at once, changed or not; else it asks before discarding a change.
    BackHandler(enabled = discardAction == null && (changed || onBack != null)) {
        if (onBack != null) onBack() else discardAction = DiscardAction.CLOSE
    }
    var coachVisible by rememberSaveable { mutableStateOf(false) }
    val needsFix = review.blanks.isNotEmpty()
    LaunchedEffect(coachMarkPending && needsFix) {
        if (coachMarkPending && needsFix) coachVisible = true
    }
    var cue by remember { mutableStateOf<StickerCue?>(null) }
    var lastSet by remember { mutableStateOf<Pair<Int, Color>?>(null) }
    val hapticsEnabled = LocalHaptics.current
    val haptics = LocalHapticFeedback.current
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
            if (painting != null) {
                PaintInstruction(painting, strings, "review:instruction")
            } else {
                Text(
                    strings.resolve(instruction(review, strings, changed)),
                    modifier = Modifier.fillMaxWidth().testTag("review:instruction"),
                )
            }
            if (oneFace) {
                FaceSelector(face, (review.outlined + review.blanks).map { it / 9 }.toSet()) { shown ->
                    face = shown
                    // Keep the selection on the shown face: its first blank, else its first sticker.
                    onReview(review.select(review.blanks.firstOrNull { it / 9 == shown } ?: (shown * 9)))
                }
            }
            lastSet?.let { (index, color) -> SetStickerLine(index, color, strings) }
            // In paint mode the selection is paused and hidden, so neither line names it.
            if (painting == null) {
                SelectedStickerLine(review, strings)
                SuspectLine(review, strings)
            }
            StickerReviewNet(
                selected = if (painting == null) review.selected else null,
                invalid = review.outlined,
                onStickerClick = { index ->
                    val locked = paint.locked
                    if (locked == null) {
                        onReview(review.select(index))
                    } else if (index % 9 != 4) {
                        // Paint mode: set this sticker, keeping the paused selection where it was, unless this paint
                        // filled the selected sticker itself: then the selection moves on as a plain paint's does.
                        val stroke = review.select(index).paint(locked)
                        val kept = if (index == review.selected) stroke.selected else review.selected
                        val painted = ScanReview(stroke.stickers, kept, stroke.evidence, stroke.checkFirst)
                        if (painted.stickers != review.stickers) {
                            changed = true
                            focusedIndex =
                                if (review.blanks.isNotEmpty() && painted.blanks.isEmpty()) {
                                    stroke.selected?.let { countsLineLedBy(painted.problems, it) }
                                } else {
                                    null
                                }
                        }
                        cue = StickerCue(index, (cue?.serial ?: 0) + 1)
                        lastSet = index to locked
                        if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        onReview(painted)
                    }
                },
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
            if (coachVisible && needsFix && painting == null) {
                CoachMark(strings) {
                    coachVisible = false
                    onCoachMarkDismissed()
                }
            }
            PaintPalette(paint, LocalRenderStyle.current, strings) { color ->
                val index = review.selected
                if (index != null && (!oneFace || index / 9 == face)) {
                    val painted = review.paint(color)
                    if (painted.stickers != review.stickers) {
                        changed = true
                        // The last blank set with the counts off: focus the counts line led by the top suspect
                        // that paint selected (#174), even beside an earlier piece problem.
                        focusedIndex =
                            if (review.blanks.isNotEmpty() && painted.blanks.isEmpty()) {
                                painted.selected?.let { countsLineLedBy(painted.problems, it) }
                            } else {
                                null
                            }
                    }
                    cue = StickerCue(index, (cue?.serial ?: 0) + 1)
                    lastSet = index to color
                    if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    onReview(painted)
                }
            }
            Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                problems.forEachIndexed { index, problem ->
                    ProblemLine(problem, problemNumbers[index], index == focused, strings, "review:problem:$index") {
                        val sticker = problemTapTarget(problem)
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
            SecondaryButton(
                onClick = { if (changed || onBack != null) discardAction = DiscardAction.CLOSE else onClose() },
                label = strings.resolve(ContentText("btn.scan.close")),
                tag = "review:close",
            )
            if (onRetakeFaces != null) {
                SecondaryButton(
                    onClick = onRetakeFaces,
                    label = strings.resolve(ContentText("btn.scan.retake-faces")),
                    tag = "review:retakeFaces",
                )
            }
            if (onEnterColors != null) {
                SecondaryButton(
                    onClick = { if (changed && enterColorsAsks) discardAction = DiscardAction.ENTER_COLORS else onEnterColors() },
                    label = strings.resolve(ContentText("btn.scan.enter-colors-instead")),
                    tag = "review:enterColors",
                )
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
                            DiscardAction.CLOSE -> onDiscard()
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

/** The review's secondary outlined button (#261): `ink-primary` label and a 1dp `hairline` border, always enabled. */
@Composable
private fun SecondaryButton(
    onClick: () -> Unit,
    label: String,
    tag: String,
) {
    val tokens = RubikTheme.tokens
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.inkPrimary),
        border = BorderStroke(1.dp, tokens.hairline),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Text(label)
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
    val reason = strings.resolve(shownReason(problem))
    val color = RubikTheme.tokens.statusInvalid
    if (number == null) {
        Text(reason, color = color, modifier = Modifier.testTag(tag))
        return
    }
    val stickers = problem.positions.map { strings.resolve(ContentText("msg.scan.sticker-ref", stickerArgs(it))) }
    val args = mapOf("number" to number, "problem" to reason, "stickers" to stickers.joinToString("; "))
    val description =
        strings.resolve(
            if (hasSuspects(problem)) {
                // The counts line's stickers are likely misreads, best first (#174).
                ContentText("msg.scan.problem-description-suspects", args)
            } else if (problem.piece == problem.positions) {
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
 * outlined piece (the problem alone when it names no piece and nothing is outlined), else valid: "All set" after the
 * Learner [fixed] stickers, "Everything was read" when the scan needed no fixes (PO 2026-11-16).
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
        problem != null && problem.piece.isEmpty() && review.outlined.isEmpty() -> shownReason(problem)
        problem != null -> ContentText("msg.scan.review-hint-invalid", mapOf("problem" to strings.resolve(shownReason(problem))))
        fixed -> ContentText("msg.scan.review-hint-fixed")
        else -> ContentText("msg.scan.review-hint-ready")
    }
}

private const val WRONG_COUNTS_KEY = "msg.validation.wrong-color-counts"

/** Whether [problem] is the wrong-counts problem listing suspects (a camera scan with evidence, #174). */
private fun hasSuspects(problem: ReviewProblem): Boolean = problem.reason.key == WRONG_COUNTS_KEY && problem.positions.isNotEmpty()

/**
 * The reason shown for [problem]: on a camera scan the wrong-counts line names a likely misread instead of the
 * manual-entry "Check the colors you entered." (#174); every other problem shows its own reason.
 */
internal fun shownReason(problem: ReviewProblem): ContentText =
    if (hasSuspects(problem)) ContentText("msg.scan.wrong-color-counts-suspects") else problem.reason

/** The sticker a problem line's tap selects: its first conflicting sticker, else the first of its piece. */
internal fun problemTapTarget(problem: ReviewProblem): Int = problem.positions.firstOrNull() ?: problem.piece.first()

/** The linked wrong-counts line among [problems] led by [selected] (its top suspect), if any. */
private fun countsLineLedBy(
    problems: List<ReviewProblem>,
    selected: Int,
): Int? =
    problems.indices.firstOrNull {
        problems[it].piece.isNotEmpty() &&
            problems[it].reason.key == WRONG_COUNTS_KEY &&
            problems[it].positions.firstOrNull() == selected
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
