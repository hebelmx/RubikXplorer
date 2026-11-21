package dev.cubentia.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.render.RenderStyle
import dev.cubentia.app.render.StickerGlyph
import dev.cubentia.app.ui.theme.Radii
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.appearance.StickerAppearance
import kotlinx.coroutines.delay
import dev.cubentia.domain.cube.Color as CubeColor
import dev.cubentia.domain.text.Text as ContentText

/** The glyph chip beside the paint instruction. */
private val PAINT_CHIP = 24.dp

private const val NO_COLOR = -1

/** How long an announcement stays in the live region, as the face-done line does. */
private const val ANNOUNCE_MS = 1_500L

/**
 * One paint-mode announcement: "Painting {color}", or "Painting off" when [color] is null; [serial] tells one event from
 * the next, so each is announced.
 */
@Immutable
internal data class PaintAnnouncement(
    val color: CubeColor?,
    val serial: Int,
)

/**
 * Paint mode (SCP 2026-11-20, #249), shared by Scan review and the Manual Cube Editor: the [locked] palette color, if
 * any, and the latest on/off [announcement] (not persisted, so a recreated screen never re-speaks it). While a color is
 * locked, each sticker tap sets it directly and auto-advance and the next-sticker pulse pause; the screens read [locked]
 * for that.
 */
@Stable
internal class PaintMode(
    locked: CubeColor? = null,
) {
    var locked: CubeColor? by mutableStateOf(locked)
        private set

    var announcement: PaintAnnouncement? by mutableStateOf(null)
        private set

    /** Locks [color] (also switching from another locked color): "Painting {color}". */
    fun lock(color: CubeColor) {
        locked = color
        announcement = PaintAnnouncement(color, nextSerial())
    }

    /** Ends paint mode, "Painting off"; ignored while nothing is locked. */
    fun stop() {
        if (locked == null) return
        locked = null
        announcement = PaintAnnouncement(null, nextSerial())
    }

    private fun nextSerial(): Int = (announcement?.serial ?: 0) + 1

    companion object {
        /** Persists only the locked color (its ordinal) across recreation. */
        val Saver: Saver<PaintMode, Int> =
            Saver(
                save = { mode -> mode.locked?.ordinal ?: NO_COLOR },
                restore = { saved -> PaintMode(saved.takeIf { it >= 0 }?.let { CubeColor.entries[it] }) },
            )
    }
}

/** A [PaintMode] that survives recreation, starting with nothing locked; new [keys] start it over. */
@Composable
internal fun rememberPaintMode(vararg keys: Any?): PaintMode = rememberSaveable(*keys, saver = PaintMode.Saver) { PaintMode() }

/** The palette name of [color] in lower case, for mid-sentence use ("Painting red"). */
@Composable
internal fun lowerColorName(color: CubeColor): String = stringResource(colorName(color)).lowercase(LocalConfiguration.current.locales[0])

/**
 * The six palette swatches (W Y R O G B) with paint mode: outside paint mode a plain tap reports [onPick] exactly as
 * before; a long-press, or the swatch's *Lock this color* action, locks that color; while a color is locked, any plain
 * tap only ends paint mode (it never paints a hidden selection), and a long-press or *Stop painting* on the locked
 * color ends it while one on another color switches the lock.
 */
@Composable
internal fun PaintPalette(
    paint: PaintMode,
    style: RenderStyle,
    strings: ContentStrings,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(Spacing.s2),
    onPick: (CubeColor) -> Unit,
) {
    val lockLabel = strings.resolve(ContentText("btn.paint.lock"))
    val stopLabel = strings.resolve(ContentText("btn.paint.stop"))
    FlowRow(horizontalArrangement = horizontalArrangement, verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
        EditorProblems.PALETTE.forEach { color ->
            val isLocked = paint.locked == color
            Swatch(
                color = color,
                style = style,
                locked = isLocked,
                onLongClick = { if (isLocked) paint.stop() else paint.lock(color) },
                paintActionLabel = if (isLocked) stopLabel else lockLabel,
            ) {
                if (paint.locked != null) paint.stop() else onPick(color)
            }
        }
    }
}

/**
 * The paint-mode announcer: a visually empty polite live region that holds each on/off announcement ("Painting red",
 * "Painting off") for about 1.5 s, so TalkBack speaks every event; otherwise it has no semantics, so it is never a
 * swipe stop.
 */
@Composable
internal fun PaintAnnouncer(
    paint: PaintMode,
    strings: ContentStrings,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf<PaintAnnouncement?>(null) }
    val event = paint.announcement
    LaunchedEffect(event) {
        if (event == null) return@LaunchedEffect
        shown = event
        delay(ANNOUNCE_MS)
        shown = null
    }
    val text =
        shown?.let { announced ->
            announced.color?.let { strings.resolve(ContentText("msg.paint.on", mapOf("color" to lowerColorName(it)))) }
                ?: strings.resolve(ContentText("msg.paint.off"))
        }
    Box(
        modifier.size(1.dp).then(
            if (text == null) {
                Modifier.clearAndSetSemantics { testTag = "paint:announce" }
            } else {
                Modifier.testTag("paint:announce").semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = text
                }
            },
        ),
    )
}

/**
 * The paint-mode instruction line ("Painting red. Tap stickers to set them. Tap red again to stop.", tagged [tag]),
 * led by a small decorative chip of the locked [color] with its glyph, hidden from TalkBack.
 */
@Composable
internal fun PaintInstruction(
    color: CubeColor,
    strings: ContentStrings,
    tag: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
) {
    val appearance = StickerAppearance.of(color)
    val shape = RoundedCornerShape(Radii.xs)
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        Box(
            Modifier
                .size(PAINT_CHIP)
                .background(Color(appearance.fill), shape)
                .border(1.dp, RubikTheme.tokens.hairline, shape)
                .clearAndSetSemantics {
                    testTag = "$tag:chip"
                    hideFromAccessibility()
                },
            contentAlignment = Alignment.Center,
        ) {
            StickerGlyph(appearance, PAINT_CHIP)
        }
        Text(
            strings.resolve(ContentText("msg.paint.instruction", mapOf("color" to lowerColorName(color)))),
            style = textStyle,
            modifier = Modifier.weight(1f).testTag(tag),
        )
    }
}
