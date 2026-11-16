package dev.cubentia.app.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.window.core.layout.WindowSizeClass
import dev.cubentia.app.R
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalRenderStyle
import dev.cubentia.app.render.Net2DRenderer
import dev.cubentia.app.ui.editor.FACE_NAMES
import dev.cubentia.app.ui.editor.FaceSelector
import dev.cubentia.app.ui.editor.StickerReviewNet
import dev.cubentia.app.ui.editor.Swatch
import dev.cubentia.app.ui.editor.colorName
import dev.cubentia.app.ui.editor.currentWindowSizeClass
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.recognition.ScanReview
import dev.cubentia.domain.text.Text as ContentText

/** Renders immutable review decisions; all sticker edits are delegated to the domain value. */
@Composable
fun ScanReviewScreen(
    review: ScanReview,
    strings: ContentStrings,
    onReview: (ScanReview) -> Unit,
    onAccept: (CubeState) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    windowSizeClass: WindowSizeClass = currentWindowSizeClass(),
) {
    var face by rememberSaveable { mutableIntStateOf((review.selected ?: 0) / 9) }
    LaunchedEffect(review.selected) { review.selected?.let { face = it / 9 } }
    val compact = !windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    BoxWithConstraints(modifier.safeDrawingPadding().testTag("review:screen")) {
        val oneFace = compact || (maxWidth - Spacing.s4 * 2) / Net2DRenderer.NET_COLUMNS < Spacing.touchMin
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(strings.resolve(ContentText("msg.scan.review-title")), style = MaterialTheme.typography.headlineSmall)
            Text(strings.resolve(ContentText("msg.scan.review-hint")))
            if (oneFace) {
                FaceSelector(face, (review.outlined + review.blanks).map { it / 9 }.toSet()) { shown ->
                    face = shown
                    // Keep the selection on the shown face: its first blank, else its first sticker.
                    onReview(review.select(review.blanks.firstOrNull { it / 9 == shown } ?: (shown * 9)))
                }
            }
            SelectedStickerLine(review, strings)
            StickerReviewNet(
                selected = review.selected,
                invalid = review.outlined,
                onStickerClick = { onReview(review.select(it)) },
                editable = { it % 9 != 4 },
                face = if (oneFace) face else null,
                unresolved = review.stickers,
                strings = strings,
                modifier =
                    if (oneFace) {
                        Modifier.widthIn(max = Spacing.touchMin * 6).fillMaxWidth().aspectRatio(1f)
                    } else {
                        Modifier.fillMaxWidth().aspectRatio(Net2DRenderer.NET_COLUMNS.toFloat() / Net2DRenderer.NET_ROWS)
                    },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s2), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                listOf(Color.WHITE, Color.YELLOW, Color.RED, Color.ORANGE, Color.GREEN, Color.BLUE).forEach { color ->
                    Swatch(color, LocalRenderStyle.current) {
                        if (!oneFace || review.selected?.div(9) == face) onReview(review.paint(color))
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
            val accepted = review.accepted
            Button(
                onClick = { accepted?.let(onAccept) },
                enabled = accepted != null,
                modifier = Modifier.fillMaxWidth().testTag("review:accept"),
            ) {
                Text(strings.resolve(ContentText("btn.scan.accept")))
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag("review:close")) {
                Text(strings.resolve(ContentText("btn.scan.close")))
            }
        }
    }
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
    val sticker =
        if (color == null) {
            strings.resolve(ContentText("msg.scan.blank-sticker", mapOf("face" to face, "position" to position.toString())))
        } else {
            stringResource(R.string.editor_sticker, stringResource(colorName(color)), face, position)
        }
    Text(
        strings.resolve(ContentText("msg.scan.selected", mapOf("sticker" to sticker))),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().testTag("review:selected").semantics { liveRegion = LiveRegionMode.Polite },
    )
}
