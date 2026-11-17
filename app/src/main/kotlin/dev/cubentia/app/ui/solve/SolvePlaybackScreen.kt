package dev.cubentia.app.ui.solve

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.scan.QuickCheckCapture
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionState
import kotlinx.coroutines.launch
import dev.cubentia.domain.text.Text as ContentText

/** A re-solve of the observed cube started from playback (*Use my cube as it is now*): running, or failed with [error]. */
data class ResolveState(
    val running: Boolean = false,
    val error: ContentText? = null,
)

@Composable
fun SolvePlaybackScreen(
    session: SessionState,
    strings: ContentStrings,
    onEvent: (SessionEvent) -> Unit,
    onClose: () -> Unit,
    onLearn: () -> Unit,
    modifier: Modifier = Modifier,
    quickCheck: QuickCheckCapture? = null,
    requestQuickCheck: suspend (Boolean) -> Unit = {},
    resolve: ResolveState = ResolveState(),
    onUseMyCube: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    val playback = rememberSolutionPlaybackState(session, onEvent = onEvent)
    Surface(modifier = modifier.fillMaxSize(), color = RubikTheme.tokens.surfaceBase, contentColor = RubikTheme.tokens.inkPrimary) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .testTag("solvePlayback:screen")
                // Expose existing tags to UiAutomator for the macrobenchmark.
                .semantics { testTagsAsResourceId = true },
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                IconButton(onClick = onClose, modifier = Modifier.testTag("solvePlayback:close")) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = strings.resolve(ContentText("btn.solve.close")))
                }
            }
            SolutionPlayback(
                playback,
                strings,
                Modifier.weight(1f),
                quickCheck = quickCheck,
                requestQuickCheck = { full ->
                    scope.launch { requestQuickCheck(full) }
                },
                resolve = resolve,
                onUseMyCube = onUseMyCube,
            )
            if (playback.atEnd) {
                TextButton(onClick = onLearn, modifier = Modifier.testTag("solve:learnOffer")) {
                    Text(strings.resolve(ContentText("msg.solve.learn-offer")))
                }
            }
        }
    }
}
