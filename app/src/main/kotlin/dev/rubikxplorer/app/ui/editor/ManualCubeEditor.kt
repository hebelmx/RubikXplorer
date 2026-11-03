package dev.rubikxplorer.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.content.ContentStrings
import dev.rubikxplorer.app.render.Net2DRenderer
import dev.rubikxplorer.app.render.RenderStyle
import dev.rubikxplorer.app.render.StickerGlyph
import dev.rubikxplorer.app.render.stickerColor
import dev.rubikxplorer.app.render.stickerGlyphInk
import dev.rubikxplorer.app.ui.theme.Radii
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.domain.appearance.StickerAppearance
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Color as CubeColor

/** Palette order, matching the glyph order `W Y R O G B`. */
private val PALETTE =
    listOf(CubeColor.WHITE, CubeColor.YELLOW, CubeColor.RED, CubeColor.ORANGE, CubeColor.GREEN, CubeColor.BLUE)

/** Face selector labels in URFDLB order. */
private val FACE_LABELS =
    listOf(
        R.string.editor_face_u,
        R.string.editor_face_r,
        R.string.editor_face_f,
        R.string.editor_face_d,
        R.string.editor_face_l,
        R.string.editor_face_b,
    )

private const val FACE_SELECTOR_COLUMNS = 3

/** Largest side of the one-face grid, so the palette stays close below it. */
private val FACE_MAX = Spacing.touchMin * 6

/**
 * The Manual Cube Editor (FR-14, UX-DR16/21/25): tap a sticker on the net, then a palette color, to
 * paint it. Centers stay at the home-frame colors. Offending pieces are outlined in `status-invalid`
 * and every problem gets its own plain-language line, resolved through [strings]. **Accept** is
 * enabled only while the entry is valid and hands [onAccept] the [CubeState] [CubeValidator]
 * produced for exactly the entered stickers. Starts from [initial] (centers overwritten), drawn in
 * [style]. Not a session: it returns its result to the caller.
 *
 * When the window is too narrow to give every net sticker a [Spacing.touchMin] cell, the editor
 * shows one face at a time with a face selector that marks faces holding outlined stickers.
 */
@Composable
fun ManualCubeEditor(
    strings: ContentStrings,
    onAccept: (CubeState) -> Unit,
    modifier: Modifier = Modifier,
    initial: Facelets = CubeState.SOLVED.toFacelets(),
    style: RenderStyle = RenderStyle(),
) {
    val state = rememberSaveable(initial, saver = ManualCubeEditorState.Saver) { ManualCubeEditorState(initial) }
    val tokens = RubikTheme.tokens
    BoxWithConstraints(modifier = modifier) {
        val onePerFace = (maxWidth - Spacing.s4 * 2) / Net2DRenderer.NET_COLUMNS < Spacing.touchMin
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (onePerFace) FaceSelector(state.face, state.facesWithProblems, state::showFace)
            StickerReviewNet(
                facelets = state.facelets,
                style = style,
                selected = state.selected,
                invalid = state.outlined,
                onStickerClick = state::select,
                editable = ManualCubeEditorState::isEditable,
                face = if (onePerFace) state.face else null,
                modifier =
                    if (onePerFace) {
                        Modifier.widthIn(max = FACE_MAX).fillMaxWidth().aspectRatio(1f)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(Net2DRenderer.NET_COLUMNS.toFloat() / Net2DRenderer.NET_ROWS)
                    },
            )
            // Wraps onto more rows when a narrow window cannot fit all six touch-sized swatches (AD-17).
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Spacing.s2),
            ) {
                PALETTE.forEach { color -> Swatch(color, style) { state.paint(color) } }
            }
            Column(
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(Spacing.s2),
            ) {
                state.problems.forEachIndexed { i, problem ->
                    Text(
                        text = strings.resolve(problem.reason),
                        color = tokens.statusInvalid,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("editor:problem:$i"),
                    )
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
    }
}

/** One palette color: a touch-sized sticker with its glyph while [style] asks for glyphs. */
@Composable
private fun Swatch(
    color: CubeColor,
    style: RenderStyle,
    onClick: () -> Unit,
) {
    val appearance = StickerAppearance.of(color)
    val fill = Color(appearance.fill)
    val ink = Color(appearance.glyphInk)
    val description = stringResource(colorName(color))
    val shape = RoundedCornerShape(Radii.sm)
    Box(
        modifier =
            Modifier
                .size(Spacing.touchMin)
                .background(fill, shape)
                .border(1.dp, RubikTheme.tokens.hairline, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .testTag("swatch:${color.name}")
                .semantics {
                    contentDescription = description
                    stickerColor = fill
                    if (style.colorBlindGlyphs) stickerGlyphInk = ink
                },
        contentAlignment = Alignment.Center,
    ) {
        if (style.colorBlindGlyphs) StickerGlyph(appearance, Spacing.touchMin)
    }
}

/**
 * One-face mode's face selector: a touch-sized tab per face, the [current] one marked selected, and
 * every face in [withProblems] badged in `status-invalid` so errors on hidden faces stay findable.
 */
@Composable
private fun FaceSelector(
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
