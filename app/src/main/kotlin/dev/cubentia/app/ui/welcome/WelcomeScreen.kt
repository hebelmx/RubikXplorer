package dev.cubentia.app.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.preferences.LocalPreferenceStore
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.application.port.FeatureFlag
import dev.cubentia.application.port.FeatureFlags
import dev.cubentia.application.preference.Preference

/**
 * The Welcome screen (FR-4, FR-17, AD-10, AD-18, UX-DR21, UX-DR22, UX Flow 8):
 * Offers beginners *I'm new* (navigates to Stage 1), proficient solvers *I can already solve it*
 * (full-Course skip check), an inline color-blind glyphs toggle (`W Y R O G B`), a conditional
 * *Start from my cube* placement action (gated behind [FeatureFlag.PLACEMENT]), and displays
 * an encouraging exit banner when a check is abandoned.
 */
@Composable
fun WelcomeScreen(
    onImNew: () -> Unit,
    onCanAlreadySolve: () -> Unit,
    flags: FeatureFlags,
    modifier: Modifier = Modifier,
    onStartFromCube: () -> Unit = {},
    abandoned: Boolean = false,
    preferenceStore: PreferenceStore = LocalPreferenceStore.current,
) {
    val tokens = RubikTheme.tokens
    val prefs by preferenceStore.preferences.collectAsState()

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(tokens.surfaceBase)
                .safeDrawingPadding()
                .testTag("welcome"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(Spacing.s4)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                color = tokens.inkPrimary,
                modifier =
                    Modifier
                        .semantics { heading() }
                        .testTag("welcome:title"),
            )

            if (abandoned) {
                Text(
                    text = stringResource(R.string.welcome_abandoned),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.inkSecondary,
                    modifier = Modifier.testTag("welcome:abandoned"),
                )
            }

            Button(
                onClick = onImNew,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("welcome:imNew"),
            ) {
                Text(stringResource(R.string.welcome_im_new))
            }

            OutlinedButton(
                onClick = onCanAlreadySolve,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("welcome:canSolve"),
            ) {
                Text(stringResource(R.string.welcome_can_solve))
            }

            if (flags.isEnabled(FeatureFlag.PLACEMENT)) {
                OutlinedButton(
                    onClick = onStartFromCube,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("welcome:startFromCube"),
                ) {
                    Text(stringResource(R.string.welcome_start_from_cube))
                }
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = prefs.colorBlindGlyphs,
                            role = Role.Switch,
                            onValueChange = { preferenceStore.update(Preference.ColorBlindGlyphs(it)) },
                        ).testTag("welcome:glyphs"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.welcome_glyphs),
                        style = MaterialTheme.typography.bodyLarge,
                        color = tokens.inkPrimary,
                    )
                    Text(
                        text = stringResource(R.string.welcome_glyph_order),
                        style = RubikTheme.textStyles.data,
                        color = tokens.inkSecondary,
                        modifier = Modifier.testTag("welcome:glyphOrder"),
                    )
                }
                Switch(
                    checked = prefs.colorBlindGlyphs,
                    onCheckedChange = null,
                )
            }
        }
    }
}
