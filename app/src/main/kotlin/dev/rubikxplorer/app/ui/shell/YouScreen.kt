package dev.rubikxplorer.app.ui.shell
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rubikxplorer.app.R

@Composable
fun YouScreen(
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.safeDrawingPadding()) {
        Text(stringResource(R.string.nav_you))
        TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().testTag("you:settings")) {
            Text(stringResource(R.string.settings_title))
        }
    }
}
