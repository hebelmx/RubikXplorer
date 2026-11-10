package dev.cubentia.app.ui.lesson

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.text.Text as ContentText

/**
 * An explicit, optional Library status change after a 2-look Objective. [failed] shows the line
 * that says the last *Add to Learning* did not save, so the Learner can try again.
 */
@Composable
internal fun TwoLookOffer(
    strings: ContentStrings,
    onAdd: () -> Unit,
    onDecline: () -> Unit,
    failed: Boolean = false,
) {
    Column(Modifier.testTag("lesson:twoLookOffer")) {
        Text(strings.resolve(ContentText("msg.lesson.two-look.offer")), color = RubikTheme.tokens.inkPrimary)
        if (failed) {
            Text(
                strings.resolve(ContentText("msg.lesson.two-look.offer-failed")),
                color = RubikTheme.tokens.statusLikely,
                modifier = Modifier.testTag("lesson:twoLookOffer:failed"),
            )
        }
        TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = Spacing.touchMin).testTag("lesson:twoLookOffer:add")) {
            Text(strings.resolve(ContentText("btn.lesson.two-look.add")), color = RubikTheme.tokens.accent)
        }
        TextButton(onClick = onDecline, modifier = Modifier.heightIn(min = Spacing.touchMin).testTag("lesson:twoLookOffer:decline")) {
            Text(strings.resolve(ContentText("btn.lesson.two-look.decline")), color = RubikTheme.tokens.inkSecondary)
        }
    }
}
