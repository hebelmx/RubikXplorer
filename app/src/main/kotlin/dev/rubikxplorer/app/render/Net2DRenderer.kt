package dev.rubikxplorer.app.render

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rubikxplorer.app.ui.theme.CubeTokens
import dev.rubikxplorer.app.ui.theme.PlexSans
import dev.rubikxplorer.app.ui.theme.Radii
import dev.rubikxplorer.domain.appearance.StickerAppearance
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.predicate.Piece
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The sticker color actually drawn, for tests (AD-4: rendered state must match engine state). */
val StickerColorKey = SemanticsPropertyKey<Color>("StickerColor")
var SemanticsPropertyReceiver.stickerColor by StickerColorKey

/** The glyph ink drawn on a sticker while color-blind glyphs are on. */
val StickerGlyphInkKey = SemanticsPropertyKey<Color>("StickerGlyphInk")
var SemanticsPropertyReceiver.stickerGlyphInk by StickerGlyphInkKey

/** Whether the dim overlay is drawn over a sticker; set only while a [NetEmphasis] is drawn. */
val StickerDimmedKey = SemanticsPropertyKey<Boolean>("StickerDimmed")
var SemanticsPropertyReceiver.stickerDimmed by StickerDimmedKey

/** Whether a sticker carries the highlight outline; set only while a [NetEmphasis] is drawn. */
val StickerHighlightedKey = SemanticsPropertyKey<Boolean>("StickerHighlighted")
var SemanticsPropertyReceiver.stickerHighlighted by StickerHighlightedKey

/**
 * Which pieces stand out on the net (a Demonstration step's focus). Centers and the [lit] pieces
 * draw normally, every other sticker gets the `sticker-dimmed-overlay`, and the stickers of the
 * [highlighted] pieces get the `sticker-highlight` outline. Pieces are mapped to stickers through the
 * facelets drawn at that moment, so the emphasis follows a moving piece. Stickers are never recolored.
 */
data class NetEmphasis(
    val lit: Set<Piece>,
    val highlighted: Set<Piece>,
)

/**
 * The first [CubeRenderer] adapter: a flat Kociemba cross net. It keeps the drawn [Facelets] in
 * Compose state and a FIFO queue of [PresentationItem]s that [Content] drains on the Compose frame
 * clock. An [PresentationItem.Animate] flips the changed stickers over at most [ITEM_MILLIS]; when
 * more than [FAST_FORWARD_AFTER] items are queued each one runs at a quarter of that. Nothing is
 * ever dropped. Submit from the main thread.
 */
class Net2DRenderer(
    initial: Facelets = CubeState.SOLVED.toFacelets(),
) : CubeRenderer {
    private var rendered by mutableStateOf(initial)
    private var changed by mutableStateOf(emptySet<Int>())
    private var progress by mutableFloatStateOf(1f)
    private val queue = Channel<PresentationItem>(Channel.UNLIMITED)
    private val queued = AtomicInteger(0)

    override fun submit(items: List<PresentationItem>) {
        items.forEach {
            queued.incrementAndGet()
            queue.trySend(it)
        }
    }

    override fun renderedFacelets(): Facelets = rendered

    /** Draws queued items one at a time until cancelled. */
    private suspend fun drain() {
        for (item in queue) {
            val behind = queued.decrementAndGet()
            try {
                when (item) {
                    is PresentationItem.Snap -> Unit
                    is PresentationItem.Animate -> animate(item.target, fast = behind >= FAST_FORWARD_AFTER)
                }
            } finally {
                // Also on cancellation, so the drawn state never lags the committed one.
                rendered = item.target
                changed = emptySet()
                progress = 1f
            }
        }
    }

    private suspend fun animate(
        target: Facelets,
        fast: Boolean,
    ) {
        val half = (if (fast) ITEM_MILLIS / FAST_FORWARD_DIVISOR else ITEM_MILLIS) / 2
        changed = target.colors.indices.filterTo(mutableSetOf()) { target.colors[it] != rendered.colors[it] }
        animate(0f, HALF, animationSpec = tween(half, easing = LinearEasing)) { value, _ -> progress = value }
        rendered = target
        animate(HALF, 1f, animationSpec = tween(half, easing = LinearEasing)) { value, _ -> progress = value }
    }

    /**
     * The net, drawn from [renderedFacelets] in [style] with the optional [emphasis] (none: every
     * sticker plain); also drains the presentation queue.
     */
    @Composable
    fun Content(
        style: RenderStyle,
        modifier: Modifier = Modifier,
        emphasis: NetEmphasis? = null,
    ) {
        LaunchedEffect(this) { drain() }
        BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
            val cell = min(maxWidth.value / NET_COLUMNS, maxHeight.value / NET_ROWS).dp
            Box(Modifier.requiredSize(cell * NET_COLUMNS, cell * NET_ROWS).testTag("net")) {
                for (index in 0 until Facelets.SIZE) {
                    val (column, row) = netCell(index)
                    Sticker(
                        index = index,
                        style = style,
                        emphasis = emphasis,
                        cell = cell,
                        modifier = Modifier.offset(cell * column, cell * row),
                    )
                }
            }
        }
    }

    @Composable
    private fun Sticker(
        index: Int,
        style: RenderStyle,
        emphasis: NetEmphasis?,
        cell: Dp,
        modifier: Modifier,
    ) {
        val appearance = StickerAppearance.of(rendered.colors[index])
        val fill = Color(appearance.fill)
        val ink = Color(appearance.glyphInk)
        val flipping = index in changed
        val piece = if (emphasis == null) null else Piece.at(rendered, index)
        val dimmed = emphasis != null && piece != null && piece !in emphasis.lit && piece !in emphasis.highlighted
        val highlighted = emphasis != null && piece != null && piece in emphasis.highlighted
        Box(
            modifier =
                modifier
                    .requiredSize(cell)
                    .padding(cell * GAP_FRACTION)
                    .graphicsLayer { if (flipping) scaleY = abs(1f - 2f * progress) }
                    .background(fill, RoundedCornerShape(Radii.xs))
                    .drawWithContent {
                        drawContent()
                        if (dimmed) drawDimOverlay()
                        if (highlighted) drawHighlight()
                    }.testTag("sticker:$index")
                    .semantics {
                        stickerColor = fill
                        if (style.colorBlindGlyphs) stickerGlyphInk = ink
                        if (emphasis != null) {
                            stickerDimmed = dimmed
                            stickerHighlighted = highlighted
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            if (style.colorBlindGlyphs) {
                // Sized in dp so font scaling never shrinks it below legibility (≥ 16dp, weight 600),
                // but never larger than the sticker, so it cannot spill onto its neighbours.
                val inner = cell.value * (1f - 2f * GAP_FRACTION)
                val glyphDp = min(max(MIN_GLYPH_DP, cell.value * GLYPH_FRACTION), inner)
                val fontSize = with(LocalDensity.current) { (glyphDp / fontScale).sp }
                Text(
                    text = appearance.glyph.toString(),
                    color = ink,
                    fontFamily = PlexSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = fontSize,
                    lineHeight = fontSize,
                    softWrap = false,
                )
            }
        }
    }

    private fun DrawScope.drawDimOverlay() {
        val radius = CornerRadius(Radii.xs.toPx())
        drawRoundRect(CubeTokens.stickerDimmedOverlay, cornerRadius = radius)
    }

    /** A 3dp `sticker-highlight` outline with a 1dp `cubie-body` inner stroke, inside the sticker. */
    private fun DrawScope.drawHighlight() {
        val outer = HIGHLIGHT_DP.dp.toPx()
        val inner = INNER_STROKE_DP.dp.toPx()
        val radius = Radii.xs.toPx()
        drawRoundRect(
            color = CubeTokens.stickerHighlight,
            topLeft = Offset(outer / 2, outer / 2),
            size = Size(size.width - outer, size.height - outer),
            cornerRadius = CornerRadius(radius),
            style = Stroke(outer),
        )
        drawRoundRect(
            color = CubeTokens.cubieBody,
            topLeft = Offset(outer + inner / 2, outer + inner / 2),
            size = Size(size.width - 2 * outer - inner, size.height - 2 * outer - inner),
            cornerRadius = CornerRadius(max(0f, radius - outer)),
            style = Stroke(inner),
        )
    }

    internal companion object {
        private const val HIGHLIGHT_DP = 3
        private const val INNER_STROKE_DP = 1

        /** Longest a single presentation item may take. */
        const val ITEM_MILLIS = 200
        const val FAST_FORWARD_AFTER = 3
        const val FAST_FORWARD_DIVISOR = 4
        private const val HALF = 0.5f
        private const val NET_COLUMNS = 12
        private const val NET_ROWS = 9
        private const val GAP_FRACTION = 0.04f
        private const val GLYPH_FRACTION = 0.55f
        private const val MIN_GLYPH_DP = 16f

        /** Top-left cell (column, row) of each URFDLB face in the Kociemba cross net. */
        private val FACE_ORIGIN = listOf(3 to 0, 6 to 3, 3 to 3, 3 to 6, 0 to 3, 9 to 3)

        fun netCell(index: Int): Pair<Int, Int> {
            val (column, row) = FACE_ORIGIN[index / 9]
            val cellInFace = index % 9
            return (column + cellInFace % 3) to (row + cellInFace / 3)
        }
    }
}
