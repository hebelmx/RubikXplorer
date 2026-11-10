package dev.cubentia.app.ui.lesson

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.cubentia.app.R
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing

/** An explicit, optional Library status change after a 2-look Objective. */
@Composable
internal fun TwoLookOffer(
    onAdd: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(Modifier.testTag("lesson:twoLookOffer")) {
        Text(stringResource(R.string.lesson_two_look_offer), color = RubikTheme.tokens.inkPrimary)
        TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = Spacing.touchMin).testTag("lesson:twoLookOffer:add")) {
            Text(stringResource(R.string.lesson_two_look_add), color = RubikTheme.tokens.accent)
        }
        TextButton(onClick = onDecline, modifier = Modifier.heightIn(min = Spacing.touchMin).testTag("lesson:twoLookOffer:decline")) {
            Text(stringResource(R.string.lesson_two_look_decline), color = RubikTheme.tokens.inkSecondary)
        }
    }
}
