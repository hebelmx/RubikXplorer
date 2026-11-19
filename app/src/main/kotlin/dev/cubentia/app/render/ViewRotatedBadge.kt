package dev.cubentia.app.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.input.ViewOrbit
import dev.cubentia.domain.text.Text as ContentText

/**
 * The stage's top-left **View rotated · Reset view** badge (UX-DR34, Story 12.17): shown only while
 * the camera orbit is off home, offering a one-tap reset with a 48dp touch target.
 */
@Composable
fun ViewRotatedBadge(
    renderer: Cube3DRenderer,
    strings: ContentStrings,
    modifier: Modifier = Modifier,
) {
    ViewRotatedBadge(
        orbit = renderer.orbit,
        strings = strings,
        onReset = renderer::resetView,
        modifier = modifier,
    )
}

@Composable
internal fun ViewRotatedBadge(
    orbit: ViewOrbit,
    strings: ContentStrings,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (orbit.isHome()) return
    val tokens = RubikTheme.tokens
    val shape = MaterialTheme.shapes.small
    val rotatedText = strings.resolve(ContentText(Cube3DRenderer.VIEW_ROTATED_KEY))
    val resetText = strings.resolve(ContentText(RESET_VIEW_KEY))
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .testTag("stage:viewRotated")
                .defaultMinSize(minWidth = Spacing.touchMin, minHeight = Spacing.touchMin)
                .clickable(
                    role = Role.Button,
                    onClickLabel = resetText,
                    onClick = onReset,
                ).padding(horizontal = Spacing.s1),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s1),
            modifier =
                Modifier
                    .clip(shape)
                    .background(tokens.surfaceRaised, shape)
                    .border(1.dp, tokens.hairline, shape)
                    .padding(horizontal = Spacing.s2, vertical = Spacing.s1),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_reset_view),
                contentDescription = null,
                tint = tokens.inkSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "$rotatedText · $resetText",
                style = MaterialTheme.typography.labelLarge,
                color = tokens.inkPrimary,
            )
        }
    }
}

internal const val RESET_VIEW_KEY = "btn.view.reset"
