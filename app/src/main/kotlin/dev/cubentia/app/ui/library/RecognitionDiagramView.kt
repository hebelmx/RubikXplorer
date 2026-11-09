package dev.cubentia.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cubentia.app.render.RenderStyle
import dev.cubentia.app.render.STICKER_GAP_FRACTION
import dev.cubentia.app.render.StickerGlyph
import dev.cubentia.app.render.stickerColor
import dev.cubentia.app.render.stickerGlyphInk
import dev.cubentia.app.ui.theme.Radii
import dev.cubentia.domain.appearance.StickerAppearance
import dev.cubentia.domain.lastlayer.RecognitionDiagram
import dev.cubentia.domain.cube.Color as CubeColor

/** Large enough that a sticker holds the 16dp color-blind glyph. */
private val DEFAULT_CELL = 20.dp

/**
 * A [diagram] drawn sticker by sticker (AD-17), announced to screen readers as one [description].
 * Cells with nothing to draw leave a gap.
 */
@Composable
internal fun RecognitionDiagramView(
    diagram: RecognitionDiagram,
    description: String,
    style: RenderStyle,
    modifier: Modifier = Modifier,
    cell: Dp = DEFAULT_CELL,
) {
    // The grid is a picture of the cube: mirroring it would show a different Case.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier =
                modifier
                    .requiredSize(cell * RecognitionDiagram.SIZE)
                    .testTag(DIAGRAM_TAG)
                    .semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            diagram.cells.forEachIndexed { index, color ->
                if (color != null) {
                    DiagramSticker(
                        index = index,
                        color = color,
                        style = style,
                        cell = cell,
                        modifier = Modifier.offset(cell * (index % RecognitionDiagram.SIZE), cell * (index / RecognitionDiagram.SIZE)),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagramSticker(
    index: Int,
    color: CubeColor,
    style: RenderStyle,
    cell: Dp,
    modifier: Modifier,
) {
    val appearance = StickerAppearance.of(color)
    val fill = Color(appearance.fill)
    val ink = Color(appearance.glyphInk)
    Box(
        modifier =
            modifier
                .requiredSize(cell)
                .padding(cell * STICKER_GAP_FRACTION)
                .background(fill, RoundedCornerShape(Radii.xs))
                .testTag("$STICKER_TAG_PREFIX$index")
                .semantics {
                    stickerColor = fill
                    if (style.colorBlindGlyphs) stickerGlyphInk = ink
                },
        contentAlignment = Alignment.Center,
    ) {
        if (style.colorBlindGlyphs) StickerGlyph(appearance, cell)
    }
}

internal const val DIAGRAM_TAG = "recognitionDiagram"
internal const val STICKER_TAG_PREFIX = "recognitionDiagram:sticker:"
