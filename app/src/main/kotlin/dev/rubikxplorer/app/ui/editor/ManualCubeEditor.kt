package dev.rubikxplorer.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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

/**
 * The Manual Cube Editor (FR-14, UX-DR16/21/25): tap a sticker on the net, then a palette color, to
 * paint it. Centers stay at the home-frame colors. Offending pieces are outlined in `status-invalid`
 * and every problem gets its own plain-language line, resolved through [strings]. **Accept** is
 * enabled only while the entry is valid and hands [onAccept] the [CubeState] [CubeValidator]
 * produced for exactly the entered stickers. Starts from [initial] (centers overwritten), drawn in
 * [style]. Not a session: it returns its result to the caller.
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
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StickerReviewNet(
            facelets = state.facelets,
            style = style,
            selected = state.selected,
            invalid = state.outlined,
            onStickerClick = state::select,
            editable = ManualCubeEditorState::isEditable,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(Net2DRenderer.NET_COLUMNS.toFloat() / Net2DRenderer.NET_ROWS),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
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
