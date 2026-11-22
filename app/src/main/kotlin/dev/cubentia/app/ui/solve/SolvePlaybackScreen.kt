package dev.cubentia.app.ui.solve

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.scan.QuickCheckCapture
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
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
    styles: SolutionStyleState? = null,
    suggestedStyle: SolutionStyle? = null,
    styleLocked: Boolean = false,
    onChooseStyle: (SolutionStyle) -> Unit = {},
    segments: SegmentLabels? = null,
    title: String? = null,
    onAgain: (() -> Unit)? = null,
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
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.testTag("solvePlayback:close")) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = strings.resolve(ContentText("btn.solve.close")))
                }
                val length = strings.resolve(ContentText("msg.playback.solution-length", mapOf("count" to playback.size)))
                if (title == null) {
                    Text(
                        length,
                        style = MaterialTheme.typography.titleMedium,
                        color = RubikTheme.tokens.inkPrimary,
                        modifier = Modifier.weight(1f).padding(end = Spacing.s4).testTag("solvePlayback:length"),
                    )
                } else {
                    // An Algorithm practice names its Case, with the Move count under it (Story 14.2).
                    Column(Modifier.weight(1f).padding(end = Spacing.s4)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            color = RubikTheme.tokens.inkPrimary,
                            modifier = Modifier.semantics { heading() }.testTag("solvePlayback:title"),
                        )
                        Text(
                            length,
                            style = MaterialTheme.typography.bodyMedium,
                            color = RubikTheme.tokens.inkSecondary,
                            modifier = Modifier.testTag("solvePlayback:length"),
                        )
                    }
                }
            }
            styles?.let { SolutionStyleChips(it, suggestedStyle, styleLocked, strings, onChooseStyle) }
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
                segments = segments,
            )
            if (playback.atEnd) {
                onAgain?.let { again ->
                    TextButton(
                        onClick = again,
                        modifier = Modifier.heightIn(min = Spacing.touchMin).testTag("practice:again"),
                    ) {
                        Text(strings.resolve(ContentText("btn.practice.again")))
                    }
                }
                TextButton(onClick = onLearn, modifier = Modifier.testTag("solve:learnOffer")) {
                    Text(strings.resolve(ContentText("msg.solve.learn-offer")))
                }
            }
        }
    }
}

/**
 * The *Solution style* control (Story 15.3, UX-DR14): one chip per [SolutionStyle], the [suggested] one badged, a
 * progress mark on the chip [state] is building, and one status line for the pending build, a timeout or failure
 * message, or, while [locked] (the real cube has left the start), the hint to go back to step 1.
 */
@Composable
internal fun SolutionStyleChips(
    state: SolutionStyleState,
    suggested: SolutionStyle?,
    locked: Boolean,
    strings: ContentStrings,
    onChoose: (SolutionStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = RubikTheme.tokens
    val groupLabel = strings.resolve(ContentText("msg.playback.style.label"))
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s1),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
            verticalArrangement = Arrangement.spacedBy(Spacing.s1),
            modifier =
                Modifier
                    .selectableGroup()
                    .semantics { contentDescription = groupLabel }
                    .testTag("solutionStyle:chips"),
        ) {
            SolutionStyle.entries.forEach { style ->
                StyleChip(style, state, suggested == style, !locked, strings) { onChoose(style) }
            }
        }
        val status =
            when {
                state.building != null -> ContentText("msg.playback.style.building")
                state.message != null -> state.message
                locked -> ContentText("msg.playback.style.locked")
                else -> null
            }
        status?.let {
            Text(
                strings.resolveNested(it),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.inkSecondary,
                modifier =
                    Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("solutionStyle:message"),
            )
        }
    }
}

@Composable
private fun StyleChip(
    style: SolutionStyle,
    state: SolutionStyleState,
    suggested: Boolean,
    enabled: Boolean,
    strings: ContentStrings,
    onClick: () -> Unit,
) {
    val tokens = RubikTheme.tokens
    val selected = state.selected == style
    val shape = MaterialTheme.shapes.small
    val content =
        when {
            selected -> tokens.onAccent
            !enabled -> tokens.inkDisabled
            else -> tokens.inkPrimary
        }
    Surface(
        shape = shape,
        color = if (selected) tokens.accent else tokens.surfaceRaised,
        contentColor = content,
        border = BorderStroke(1.dp, if (selected) tokens.accent else tokens.hairline),
        modifier =
            Modifier
                .heightIn(min = Spacing.touchMin)
                .clip(shape)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .testTag("solutionStyle:${style.name}"),
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s1),
        ) {
            if (state.building == style) {
                CircularProgressIndicator(
                    Modifier.size(14.dp).testTag("solutionStyle:${style.name}:building"),
                    color = content,
                    strokeWidth = 2.dp,
                )
            }
            Text(strings.resolve(ContentText(style.label)), style = MaterialTheme.typography.labelLarge, color = content)
            if (suggested) {
                Text(
                    strings.resolve(ContentText("msg.playback.style.suggested")),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) content else tokens.accent,
                    modifier = Modifier.testTag("solutionStyle:${style.name}:suggested"),
                )
            }
        }
    }
}

/** [text] with any content [ContentText] argument (a method name) resolved first. */
private fun ContentStrings.resolveNested(text: ContentText): String =
    resolve(ContentText(text.key, text.args.mapValues { (_, value) -> (value as? ContentText)?.let(::resolve) ?: value }))
