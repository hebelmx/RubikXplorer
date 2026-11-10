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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionState
import dev.cubentia.domain.text.Text as ContentText

@Composable
fun SolvePlaybackScreen(
    session: SessionState,
    strings: ContentStrings,
    onEvent: (SessionEvent) -> Unit,
    onClose: () -> Unit,
    onLearn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    val playback = rememberSolutionPlaybackState(session, onEvent = onEvent)
    Column(modifier.fillMaxSize().safeDrawingPadding().testTag("solvePlayback:screen")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("solvePlayback:close")) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = strings.resolve(ContentText("btn.solve.close")))
            }
        }
        SolutionPlayback(playback, strings, Modifier.weight(1f))
        if (playback.atEnd) {
            TextButton(onClick = onLearn, modifier = Modifier.testTag("solve:learnOffer")) {
                Text(strings.resolve(ContentText("msg.solve.learn-offer")))
            }
        }
    }
}
