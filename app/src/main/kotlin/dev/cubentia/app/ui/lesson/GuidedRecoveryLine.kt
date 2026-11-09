package dev.cubentia.app.ui.lesson

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.application.session.GuidedRecovery
import dev.cubentia.application.usecase.PlanRecovery
import dev.cubentia.application.usecase.result
import dev.cubentia.domain.cube.Move

/** Which Guided recovery line shows: the plan is being found, is ready, or could not be found. */
internal enum class GuidedRecoveryKind(
    val tag: String,
) {
    PENDING("lesson:guidedRecovery:pending"),
    PLAN("lesson:guidedRecovery:plan"),
    NOT_FOUND("lesson:guidedRecovery:notFound"),
}

/** The Guided recovery line, already resolved: its [kind], its content [text] and the plan's [moves] (empty unless a plan). */
internal data class GuidedRecoveryContent(
    val kind: GuidedRecoveryKind,
    val text: String,
    val moves: List<Move> = emptyList(),
)

/** The line for the session's [recovery], resolved through [strings]; null while there is none. */
internal fun guidedRecoveryContent(
    recovery: GuidedRecovery,
    strings: ContentStrings,
): GuidedRecoveryContent? =
    when (recovery) {
        GuidedRecovery.None -> null
        is GuidedRecovery.Planning -> GuidedRecoveryContent(GuidedRecoveryKind.PENDING, strings.resolve(recovery.text))
        is GuidedRecovery.Planned -> GuidedRecoveryContent(GuidedRecoveryKind.PLAN, strings.resolve(recovery.text), recovery.moves)
        GuidedRecovery.NotFound -> GuidedRecoveryContent(GuidedRecoveryKind.NOT_FOUND, strings.resolve(GuidedRecovery.NotFound.text))
    }

/**
 * The Guided recovery (UX-DR11): inline in the step card, never a dialog, neutral ink in one polite
 * live region. Pending says it is looking, a plan reads "1 move — your cross stays" with its Moves as
 * notation chips (the Learner turns them; nothing is applied for them), and no plan says to undo or
 * start the step again. It decides nothing: the session's Guided recovery is resolved by the caller.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuidedRecoveryLine(
    content: GuidedRecoveryContent,
    modifier: Modifier = Modifier,
) {
    val tokens = RubikTheme.tokens
    Column(
        modifier =
            modifier
                .testTag(content.kind.tag)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.s1),
    ) {
        Text(content.text, style = MaterialTheme.typography.bodyLarge, color = tokens.inkPrimary)
        if (content.moves.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                verticalArrangement = Arrangement.spacedBy(Spacing.s2),
            ) {
                content.moves.forEach { NotationChip(it) }
            }
        }
    }
}

/**
 * Runs the session's pending Guided recovery search (AD-3, AD-8): while [host]'s session is Planning,
 * calls [planRecovery] for that Effect and dispatches its revision-tagged result. Keyed on the Effect,
 * so the search is cancelled as soon as it is no longer the current one (a Move, Undo or any other
 * event ended it, or a re-offer replaced it). It holds no state: the Guided recovery lives in the session.
 */
@Composable
internal fun GuidedRecoveryRunner(
    host: SessionHost,
    planRecovery: PlanRecovery,
) {
    val session by host.state.collectAsState()
    val effect = (session.guidedRecovery as? GuidedRecovery.Planning)?.effect ?: return
    LaunchedEffect(effect) {
        val moves = planRecovery(effect)
        host.dispatch { effect.result(moves, it) }
    }
}
