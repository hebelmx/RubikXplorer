package dev.rubikxplorer.app.ui.freecube

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.domain.cube.CubeValidator
import dev.rubikxplorer.domain.cube.ValidationResult
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved

/**
 * Debug-only Objective proof of concept (P0.1 exit): the [WhiteCrossSolved] result and progress, how the
 * last Move changed it, whether the committed state validates, and whether the net has caught up.
 */
@Composable
internal fun ObjectiveOverlay(
    viewModel: FreeCubeViewModel,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.history.state
    val facelets = state.toFacelets()
    val progress = WhiteCrossSolved.progress(state)
    val validated = CubeValidator.validate(facelets)
    val valid = validated is ValidationResult.Valid && validated.state.cubies == state.cubies
    val inSync = viewModel.renderer.renderedFacelets() == facelets
    val change =
        when (viewModel.lastChange) {
            ProgressChange.CLOSER -> R.string.objective_change_closer
            ProgressChange.UNCHANGED -> R.string.objective_change_unchanged
            ProgressChange.FURTHER -> R.string.objective_change_further
            null -> R.string.objective_change_none
        }
    Column(modifier = modifier.testTag("debug:objectiveOverlay"), verticalArrangement = Arrangement.spacedBy(Spacing.s1)) {
        Line(
            "objective:satisfied",
            stringResource(if (WhiteCrossSolved.isSatisfied(state)) R.string.objective_solved else R.string.objective_not_solved),
        )
        Line("objective:progress", stringResource(R.string.objective_progress, progress.done, progress.total))
        Line("objective:change", stringResource(R.string.objective_change, stringResource(change)))
        Line(
            "objective:valid",
            stringResource(R.string.objective_valid, stringResource(if (valid) R.string.objective_yes else R.string.objective_no)),
        )
        Line("objective:sync", stringResource(if (inSync) R.string.objective_in_sync else R.string.objective_catching_up))
    }
}

@Composable
private fun Line(
    tag: String,
    text: String,
) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = RubikTheme.tokens.inkPrimary, modifier = Modifier.testTag(tag))
}
