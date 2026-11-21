package dev.cubentia.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import dev.cubentia.app.R
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalHaptics
import dev.cubentia.app.preferences.LocalRenderStyle
import dev.cubentia.app.render.Net2DRenderer
import dev.cubentia.app.render.RenderStyle
import dev.cubentia.app.render.StickerGlyph
import dev.cubentia.app.render.stickerColor
import dev.cubentia.app.render.stickerGlyphInk
import dev.cubentia.app.ui.theme.Radii
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.appearance.StickerAppearance
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.recognition.ScanAssembler
import kotlinx.coroutines.delay
import dev.cubentia.domain.cube.Color as CubeColor
import dev.cubentia.domain.text.Text as ContentText

/** Face selector labels (and face names in messages) in URFDLB order. */
internal val FACE_LABELS =
    listOf(
        R.string.editor_face_u,
        R.string.editor_face_r,
        R.string.editor_face_f,
        R.string.editor_face_d,
        R.string.editor_face_l,
        R.string.editor_face_b,
    )

private const val FACE_SELECTOR_COLUMNS = 3

private const val PER_FACE = 9

/** Stickers of each color on a real cube. */
private const val PER_COLOR = 9

/** Largest side of the one-face grid, so the palette stays close below it. */
private val FACE_MAX = Spacing.touchMin * 6

/** The hold bands beside the shown face's edges (DESIGN.md, #253). */
private val HOLD_BAND = 6.dp

/** Room kept around the shown face for its bands: the band, plus a gap past the cube body's rim. */
private val HOLD_INSET = HOLD_BAND + Spacing.s1 + 2.dp

/** How long the face-done line shows. */
private const val FACE_DONE_MS = 1_500L

private val TAB_DOT = 6.dp

private const val HOLD_SIDES = 4

private val HOLD_NAMES = listOf("top", "right", "bottom", "left")

/** A locked paint-mode swatch's outline (DESIGN.md, #249): the retake-selected face's 3dp. */
private val LOCK_OUTLINE = 3.dp

/** The locked swatch's ✓ badge, as a fraction of the swatch, and its ✓ as a fraction of the badge. */
private const val LOCK_BADGE_FRACTION = 0.4f
private const val LOCK_CHECK_TEXT_FRACTION = 0.6f

/**
 * The Manual Cube Editor (FR-14, UX-DR16/21/25): tap a sticker on the net, then a palette color, to
 * paint it, with the update cue and a light haptic tick. The selection then auto-advances to the next
 * editable sticker (stopping at the last one), announced in a polite live region; tap any sticker to
 * fix it. Centers stay at the home-frame colors.
 * Problem lines are specific (#253): counts name each color that is off, pieces are named by their faces and numbered
 * like Scan review, with numbered markers on their stickers. A [fresh] entry (the default solved cube with no caller
 * cube) shows "{n} faces left to enter" instead of any problem until every face is entered, and starts with the first
 * sticker selected. Each face may be entered in any rotation: a unique fit through ScanAssembler is redrawn with a
 * note, otherwise an instruction line names the face and its hold. **Accept** is
 * enabled only while the entry is valid and hands [onAccept] the [CubeState] [CubeValidator]
 * produced for exactly the stickers shown. Starts from [initial] (centers overwritten) with [initialSet] stickers
 * already set, drawn in [style]. Not a session: it returns its result to the caller, and reports each paint's
 * stickers and set stickers to [onChange], so a caller can keep an unaccepted draft across leaving composition.
 *
 * Layout is keyed on [windowSizeClass] (AD-17): the compact width class edits one face at a time
 * with a face selector (✓ when a face is complete, a warning dot when a problem points at it) and bands showing the
 * neighbouring centers' colors around the shown face; wider classes show the full net under the same face tabs as a
 * status strip (a tap selects that face's first sticker), with no bands since the net shows every neighbour.
 * As a fallback, a wider class still gets one-face mode whenever the available width cannot give
 * every net sticker a [Spacing.touchMin] cell.
 */
@Composable
fun ManualCubeEditor(
    strings: ContentStrings,
    onAccept: (CubeState) -> Unit,
    modifier: Modifier = Modifier,
    initial: Facelets = CubeState.SOLVED.toFacelets(),
    style: RenderStyle = LocalRenderStyle.current,
    windowSizeClass: WindowSizeClass = currentWindowSizeClass(),
    fresh: Boolean = false,
    initialSet: Set<Int> = emptySet(),
    onChange: (Facelets, Set<Int>) -> Unit = { _, _ -> },
) {
    val compact = !windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val state =
        rememberSaveable(initial, fresh, saver = ManualCubeEditorState.Saver) {
            ManualCubeEditorState(initial, fresh, initialSet)
        }
    val tokens = RubikTheme.tokens
    val hapticsEnabled = LocalHaptics.current
    val haptics = LocalHapticFeedback.current
    var cue by remember { mutableStateOf<StickerCue?>(null) }
    // Paint mode (#249): while a color is locked, a sticker tap sets it directly and the selection pauses.
    val paint = rememberPaintMode(initial, fresh)
    val painting = paint.locked
    val faceNames = FACE_LABELS.map { stringResource(it) }
    val colorNames = CubeColor.entries.associateWith { stringResource(colorName(it)) }
    BoxWithConstraints(modifier = modifier) {
        val onePerFace = compact || (maxWidth - Spacing.s4 * 2) / Net2DRenderer.NET_COLUMNS < Spacing.touchMin
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // One-face mode picks the shown face; the full net shows the same tabs as a status strip above it (#253),
            // where a tap moves the selection to that face's first sticker.
            if (onePerFace) {
                EditorFaceSelector(state, state.face, FACE_SELECTOR_COLUMNS, strings, state::showFace)
            } else {
                EditorFaceSelector(state, state.selected?.let { it / PER_FACE }, FACE_LABELS.size, strings, state::selectFace)
            }
            val net: @Composable (Modifier) -> Unit = { netModifier ->
                StickerReviewNet(
                    facelets = state.facelets,
                    style = style,
                    selected = if (painting == null) state.selected else null,
                    invalid = state.outlined,
                    onStickerClick = { index ->
                        val locked = paint.locked
                        if (locked == null) {
                            state.select(index)
                        } else if (ManualCubeEditorState.isEditable(index)) {
                            state.paintAt(index, locked)
                            onChange(state.facelets, state.set)
                            cue = StickerCue(state.cueIndex ?: index, (cue?.serial ?: 0) + 1)
                            if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        }
                    },
                    editable = ManualCubeEditorState::isEditable,
                    face = if (onePerFace) state.face else null,
                    strings = strings,
                    cue = cue,
                    problemMarks = state.marks,
                    modifier = netModifier,
                )
            }
            if (onePerFace) {
                Box(Modifier.widthIn(max = FACE_MAX).fillMaxWidth().aspectRatio(1f)) {
                    net(Modifier.fillMaxSize().padding(HOLD_INSET))
                    HoldBands(state.face, style)
                }
            } else {
                net(Modifier.fillMaxWidth().aspectRatio(Net2DRenderer.NET_COLUMNS.toFloat() / Net2DRenderer.NET_ROWS))
            }
            FaceDoneLine(state.faceDone, faceNames, strings)
            // Wraps onto more rows when a narrow window cannot fit all six touch-sized swatches (AD-17).
            PaintPalette(paint, style, strings, Arrangement.spacedBy(Spacing.s2, Alignment.CenterHorizontally)) { color ->
                state.selected?.let { index ->
                    state.paint(color)
                    onChange(state.facelets, state.set)
                    // The painted sticker, moved with its face when the paint turned that face.
                    cue = StickerCue(state.cueIndex ?: index, (cue?.serial ?: 0) + 1)
                    if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
            if (painting != null) {
                PaintInstruction(painting, strings, "editor:instruction", textStyle = MaterialTheme.typography.bodyMedium)
            } else {
                SelectedStickerLine(state, strings)
            }
            instruction(state, strings, faceNames, colorNames)?.takeIf { painting == null }?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().testTag("editor:instruction").semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (state.turned.isNotEmpty()) {
                val turned =
                    strings.resolve(
                        ContentText(
                            "msg.editor.turned",
                            mapOf("count" to state.turned.size, "faces" to joinNames(strings, state.turned.map(faceNames::get))),
                        ),
                    )
                Text(
                    turned,
                    color = tokens.inkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().testTag("editor:turned").semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(Spacing.s2),
            ) {
                var number = 0
                state.lines.forEachIndexed { i, problem ->
                    val text = problemText(problem, strings, faceNames, colorNames)
                    if (problem is EditorProblem.Piece) {
                        number++
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag("editor:problem:$i").semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                        ) {
                            ProblemTag(number, focused = false, tag = "editor:problem:$i:tag")
                            Text(text, color = tokens.statusInvalid, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Text(
                            text = text,
                            color = tokens.statusInvalid,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("editor:problem:$i"),
                        )
                    }
                }
            }
            val valid = state.validState
            Button(
                onClick = { valid?.let(onAccept) },
                enabled = valid != null,
                modifier = Modifier.testTag("editor:accept"),
            ) {
                Text(stringResource(R.string.editor_accept))
            }
        }
        PaintAnnouncer(paint, strings)
    }
}

/** [items] joined as one phrase: "a", "a and b", "a, b and c", "a, b, c and d". */
private fun joinNames(
    strings: ContentStrings,
    items: List<String>,
): String =
    when (items.size) {
        0 -> {
            ""
        }

        1 -> {
            items.single()
        }

        2 -> {
            strings.resolve(ContentText("msg.editor.list-two", mapOf("a" to items[0], "b" to items[1])))
        }

        3 -> {
            strings.resolve(ContentText("msg.editor.list-three", mapOf("a" to items[0], "b" to items[1], "c" to items[2])))
        }

        else -> {
            strings.resolve(
                ContentText(
                    "msg.editor.list-more",
                    mapOf(
                        "rest" to items.dropLast(1).joinToString(", "),
                        "last" to items.last(),
                    ),
                ),
            )
        }
    }

/** The instruction line: faces left to enter on a fresh entry, else the face to check and its hold; null otherwise. */
private fun instruction(
    state: ManualCubeEditorState,
    strings: ContentStrings,
    faceNames: List<String>,
    colorNames: Map<CubeColor, String>,
): String? {
    if (state.fresh && state.unentered.isNotEmpty()) {
        val faces = state.unentered.map(faceNames::get).joinToString(", ")
        return strings.resolve(ContentText("msg.editor.faces-left", mapOf("count" to state.unentered.size, "faces" to faces)))
    }
    val face = state.checkFace ?: return null
    return strings.resolve(checkFaceText(face, faceNames, colorNames))
}

/**
 * The "Check the … face" line for [face] (URFDLB index) with its hold: Up looks at the top with green toward the Learner;
 * a side face is held white on top with its center toward the Learner; Down is held green on top (its top neighbour in
 * the net) with yellow toward the Learner, so it is not read 180° off when peeking underneath.
 */
internal fun checkFaceText(
    face: Int,
    faceNames: List<String>,
    colorNames: Map<CubeColor, String>,
): ContentText {
    val name = faceNames[face]
    val front = colorNames.getValue(CubeColor.home(Face.F)).lowercase()
    return when (Face.entries[face]) {
        Face.U -> {
            ContentText("msg.editor.check-face-top", mapOf("face" to name, "front" to front))
        }

        else -> {
            val up = if (Face.entries[face] == Face.D) Face.F else Face.U
            ContentText(
                "msg.editor.check-face-side",
                mapOf(
                    "face" to name,
                    "up" to colorNames.getValue(CubeColor.home(up)).lowercase(),
                    "center" to colorNames.getValue(CubeColor.home(Face.entries[face])).lowercase(),
                ),
            )
        }
    }
}

/** One problem line's text. */
private fun problemText(
    problem: EditorProblem,
    strings: ContentStrings,
    faceNames: List<String>,
    colorNames: Map<CubeColor, String>,
): String =
    when (problem) {
        is EditorProblem.Counts -> {
            val parts =
                problem.off.joinToString(" · ") { (color, count) ->
                    val over = count > PER_COLOR
                    strings.resolve(
                        ContentText(
                            if (over) "msg.editor.count-over" else "msg.editor.count-under",
                            mapOf(
                                "color" to colorNames.getValue(color),
                                "count" to count,
                                "diff" to if (over) count - PER_COLOR else PER_COLOR - count,
                            ),
                        ),
                    )
                }
            strings.resolve(ContentText("msg.editor.counts", mapOf("parts" to parts)))
        }

        is EditorProblem.Piece -> {
            strings.resolve(
                ContentText(
                    problem.key,
                    mapOf(
                        "piece" to problem.kind.id,
                        "faces" to joinNames(strings, problem.faces.map(faceNames::get)),
                        "count" to problem.count,
                    ),
                ),
            )
        }

        is EditorProblem.Global -> {
            strings.resolve(problem.reason)
        }
    }

/**
 * The face-done line ("Front done ✓ → Right") for about 1.5 s per [event], in a slot whose height is always kept so the
 * layout never moves; a polite live region that TalkBack reads as "Front face done. Right face next."
 */
@Composable
private fun FaceDoneLine(
    event: FaceDone?,
    faceNames: List<String>,
    strings: ContentStrings,
) {
    var shown by remember { mutableStateOf<FaceDone?>(null) }
    LaunchedEffect(event) {
        if (event == null) return@LaunchedEffect
        shown = event
        delay(FACE_DONE_MS)
        shown = null
    }
    val done = shown
    val args = done?.let { mapOf("face" to faceNames[it.face], "next" to faceNames[it.next]) }
    // The slot always holds one line of text (blank while hidden), so showing the line never moves the layout.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            if (args == null) " " else strings.resolve(ContentText("msg.editor.face-done", args)),
            color = RubikTheme.tokens.inkSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier =
                if (args == null) {
                    Modifier.clearAndSetSemantics {}
                } else {
                    val spoken = strings.resolve(ContentText("msg.editor.face-done-spoken", args))
                    Modifier.testTag("editor:faceDone").semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = spoken
                    }
                },
        )
    }
}

/**
 * One-face mode's hold: a 6dp band beside each edge of [face] in its net neighbour's center color (with its glyph while
 * [style] asks for glyphs), so the hold the face is entered in is always visible. Decorative: hidden from TalkBack.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.HoldBands(
    face: Int,
    style: RenderStyle,
) {
    val neighbors = ScanAssembler.netNeighbors(Face.entries[face])
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        repeat(HOLD_SIDES) { side ->
            val appearance = StickerAppearance.of(CubeColor.home(neighbors[side]))
            val fill = Color(appearance.fill)
            val ink = Color(appearance.glyphInk)
            val horizontal = side % 2 == 0
            val placement =
                when (side) {
                    0 -> Modifier.align(Alignment.TopCenter)
                    1 -> Modifier.align(AbsoluteAlignment.CenterRight)
                    2 -> Modifier.align(Alignment.BottomCenter)
                    else -> Modifier.align(AbsoluteAlignment.CenterLeft)
                }
            val size =
                if (horizontal) {
                    Modifier.padding(horizontal = HOLD_INSET).fillMaxWidth().height(HOLD_BAND)
                } else {
                    Modifier.padding(vertical = HOLD_INSET).fillMaxHeight().width(HOLD_BAND)
                }
            Box(
                placement
                    .then(size)
                    .background(fill, RoundedCornerShape(Radii.xs))
                    .clearAndSetSemantics {
                        testTag = "editor:hold:${HOLD_NAMES[side]}"
                        stickerColor = fill
                        if (style.colorBlindGlyphs) stickerGlyphInk = ink
                        hideFromAccessibility()
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (style.colorBlindGlyphs) {
                    Text(
                        appearance.glyph.toString(),
                        color = ink,
                        fontSize = with(LocalDensity.current) { HOLD_BAND.toSp() },
                        lineHeight = with(LocalDensity.current) { HOLD_BAND.toSp() },
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** Names the selected sticker in a polite live region, so auto-advance is announced to TalkBack. */
@Composable
private fun SelectedStickerLine(
    state: ManualCubeEditorState,
    strings: ContentStrings,
) {
    val index = state.selected ?: return
    val sticker =
        stringResource(
            R.string.editor_sticker,
            stringResource(colorName(state.facelets.colors[index])),
            stringResource(FACE_NAMES[index / PER_FACE]),
            index % PER_FACE + 1,
        )
    Text(
        strings.resolve(ContentText("msg.editor.selected", mapOf("sticker" to sticker))),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().testTag("editor:selected").semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The [WindowSizeClass] of the current window, from its container size. */
@Composable
internal fun currentWindowSizeClass(): WindowSizeClass {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return with(density) {
        WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(size.width.toDp().value, size.height.toDp().value)
    }
}

/**
 * One palette color: a touch-sized sticker with its glyph while [style] asks for glyphs. With [onLongClick] (paint mode,
 * #249) a long-press or the [paintActionLabel] accessibility action calls it, and the swatch reports [locked] as its
 * selected state; [locked] draws a 3dp `accent` outline and a ✓ badge (`on-accent` on an `accent` dot), as on a
 * retake-selected face.
 */
@Composable
internal fun Swatch(
    color: CubeColor,
    style: RenderStyle,
    locked: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    paintActionLabel: String? = null,
    onClick: () -> Unit,
) {
    val appearance = StickerAppearance.of(color)
    val fill = Color(appearance.fill)
    val ink = Color(appearance.glyphInk)
    val description = stringResource(colorName(color))
    val shape = RoundedCornerShape(Radii.sm)
    val tokens = RubikTheme.tokens
    val badge = Spacing.touchMin * LOCK_BADGE_FRACTION
    val hapticsEnabled = LocalHaptics.current
    // Sized to the badge, not the font scale, so the ✓ never overflows its dot at 200%.
    val checkSize = with(LocalDensity.current) { (badge * LOCK_CHECK_TEXT_FRACTION).toSp() }
    Box(
        modifier =
            Modifier
                .size(Spacing.touchMin)
                .background(fill, shape)
                .border(if (locked) LOCK_OUTLINE else 1.dp, if (locked) tokens.accent else tokens.hairline, shape)
                .then(
                    if (onLongClick == null) {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else {
                        Modifier.combinedClickable(
                            role = Role.Button,
                            // The long-press tick follows the Learner's haptics setting, like every other tick.
                            hapticFeedbackEnabled = hapticsEnabled,
                            onLongClickLabel = paintActionLabel,
                            onLongClick = onLongClick,
                            onClick = onClick,
                        )
                    },
                ).testTag("swatch:${color.name}")
                .semantics {
                    contentDescription = description
                    stickerColor = fill
                    if (style.colorBlindGlyphs) stickerGlyphInk = ink
                    if (onLongClick != null) {
                        selected = locked
                        if (paintActionLabel != null) {
                            customActions =
                                listOf(
                                    CustomAccessibilityAction(paintActionLabel) {
                                        onLongClick()
                                        true
                                    },
                                )
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        if (style.colorBlindGlyphs) StickerGlyph(appearance, Spacing.touchMin)
        if (locked) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .requiredSize(badge)
                    .background(tokens.accent, CircleShape)
                    .clearAndSetSemantics { testTag = "swatch:${color.name}:check" },
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = tokens.onAccent, fontSize = checkSize, lineHeight = checkSize, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * The editor's face tabs: a touch-sized tab per face, the [current] one (if any) marked selected, at most [columns] per
 * row; a complete face shows a ✓ in `ink-secondary`, a face a problem points at a small `sticker-highlight` dot (never
 * red), so errors on hidden faces stay findable, and a face turned to match says so to TalkBack. One-face mode uses it
 * to pick the shown face; the full net shows it above the net as a status strip.
 */
@Composable
private fun EditorFaceSelector(
    state: ManualCubeEditorState,
    current: Int?,
    columns: Int,
    strings: ContentStrings,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
        maxItemsInEachRow = columns,
    ) {
        FACE_LABELS.forEachIndexed { face, label ->
            EditorFaceTab(
                face = face,
                label = stringResource(label),
                isCurrent = face == current,
                complete = state.complete(face),
                hasProblems = face in state.facesWithProblems,
                turned = face in state.turned,
                strings = strings,
                onClick = { onSelect(face) },
            )
        }
    }
}

@Composable
private fun EditorFaceTab(
    face: Int,
    label: String,
    isCurrent: Boolean,
    complete: Boolean,
    hasProblems: Boolean,
    turned: Boolean,
    strings: ContentStrings,
    onClick: () -> Unit,
) {
    val tokens = RubikTheme.tokens
    val description = stringResource(R.string.editor_face_tab, label)
    val states =
        listOfNotNull(
            stringResource(R.string.editor_face_invalid).takeIf { hasProblems },
            strings.resolve(ContentText("msg.editor.face-complete")).takeIf { complete },
            strings.resolve(ContentText("msg.editor.face-turned")).takeIf { turned },
        ).joinToString(", ")
    val shape = RoundedCornerShape(Radii.sm)
    Box(
        modifier =
            Modifier
                .widthIn(min = Spacing.touchMin)
                .heightIn(min = Spacing.touchMin)
                .border(if (isCurrent) 2.dp else 1.dp, if (isCurrent) tokens.accent else tokens.hairline, shape)
                .selectable(selected = isCurrent, role = Role.Tab, onClick = onClick)
                .testTag("editor:face:$face")
                .semantics {
                    contentDescription = description
                    if (states.isNotEmpty()) stateDescription = states
                },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s1),
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
            if (complete) {
                Text(
                    "✓",
                    color = tokens.inkSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clearAndSetSemantics { testTag = "editor:face:$face:done" },
                )
            }
            if (hasProblems) {
                Box(
                    Modifier
                        .size(TAB_DOT)
                        .background(tokens.stickerHighlight, CircleShape)
                        .clearAndSetSemantics { testTag = "editor:face:$face:dot" },
                )
            }
        }
    }
}

/**
 * Scan review's one-face face selector: a touch-sized tab per face, the [current] one marked selected, and
 * every face in [withProblems] badged in `status-invalid` so errors on hidden faces stay findable.
 */
@Composable
internal fun FaceSelector(
    current: Int,
    withProblems: Set<Int>,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
        maxItemsInEachRow = FACE_SELECTOR_COLUMNS,
    ) {
        FACE_LABELS.forEachIndexed { face, label ->
            FaceTab(face, stringResource(label), face == current, face in withProblems) { onSelect(face) }
        }
    }
}

@Composable
private fun FaceTab(
    face: Int,
    label: String,
    isCurrent: Boolean,
    hasProblems: Boolean,
    onClick: () -> Unit,
) {
    val tokens = RubikTheme.tokens
    val description = stringResource(R.string.editor_face_tab, label)
    val problemState = stringResource(R.string.editor_face_invalid)
    val shape = RoundedCornerShape(Radii.sm)
    Box(
        modifier =
            Modifier
                .widthIn(min = Spacing.touchMin)
                .heightIn(min = Spacing.touchMin)
                .border(if (isCurrent) 2.dp else 1.dp, if (isCurrent) tokens.accent else tokens.hairline, shape)
                .selectable(selected = isCurrent, role = Role.Tab, onClick = onClick)
                .testTag("editor:face:$face")
                .semantics {
                    contentDescription = description
                    if (hasProblems) stateDescription = problemState
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (hasProblems) stringResource(R.string.editor_face_with_problems, label) else label,
            color = if (hasProblems) tokens.statusInvalid else Color.Unspecified,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = Spacing.s2).clearAndSetSemantics {},
        )
    }
}
