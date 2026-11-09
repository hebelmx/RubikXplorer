package dev.cubentia.app.ui.settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.preferences.LocalPreferenceStore
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.application.preference.AnimationSpeed
import dev.cubentia.application.preference.Preference
import dev.cubentia.application.preference.ThemeChoice

@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onResetProgress: () -> Unit = {},
    store: PreferenceStore = LocalPreferenceStore.current,
) {
    val prefs by store.preferences.collectAsState()
    var confirm by rememberSaveable { mutableStateOf(false) }
    var confirmProgress by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    val tokens = RubikTheme.tokens
    CompositionLocalProvider(LocalContentColor provides tokens.inkPrimary) {
        Column(
            Modifier
                .fillMaxSize()
                .background(tokens.surfaceBase)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .testTag("settings:screen"),
        ) {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("settings:close")) {
                Text(stringResource(R.string.settings_close))
            }
            Text(stringResource(R.string.settings_title))
            Text(stringResource(R.string.settings_theme))
            Column(Modifier.selectableGroup()) {
                ThemeChoice.entries.forEach { choice ->
                    val label =
                        when (choice) {
                            ThemeChoice.SYSTEM -> R.string.settings_system
                            ThemeChoice.LIGHT -> R.string.settings_light
                            ThemeChoice.DARK -> R.string.settings_dark
                        }
                    SettingRadio(
                        label,
                        "theme:${choice.name.lowercase()}",
                        prefs.theme == choice,
                    ) { store.update(Preference.Theme(choice)) }
                }
            }
            SettingSwitch(R.string.settings_glyphs, "glyphs", prefs.colorBlindGlyphs) { store.update(Preference.ColorBlindGlyphs(it)) }
            Text(stringResource(R.string.settings_speed))
            Column(Modifier.selectableGroup()) {
                AnimationSpeed.entries.forEach { speed ->
                    val label =
                        when (speed) {
                            AnimationSpeed.SLOW -> R.string.settings_slow
                            AnimationSpeed.NORMAL -> R.string.settings_normal
                            AnimationSpeed.FAST -> R.string.settings_fast
                        }
                    SettingRadio(label, "speed:${speed.name.lowercase()}", prefs.animationSpeed == speed) {
                        store.update(Preference.Speed(speed))
                    }
                }
            }
            SettingSwitch(R.string.free_cube_haptics, "haptics", prefs.haptics) { store.update(Preference.Haptics(it)) }
            SettingSwitch(R.string.settings_reduce_motion, "reduceMotion", prefs.reduceMotion) { store.update(Preference.ReduceMotion(it)) }
            TextButton(onClick = {
                confirm = true
            }, modifier = Modifier.heightIn(min = 48.dp).testTag("settings:reset")) { Text(stringResource(R.string.settings_reset)) }
            TextButton(onClick = {
                confirmProgress = true
            }, modifier = Modifier.heightIn(min = 48.dp).testTag("settings:resetProgress")) {
                Text(stringResource(R.string.settings_reset_progress))
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.settings_reset)) },
            text = { Text(stringResource(R.string.settings_reset_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.reset()
                        confirm = false
                    },
                    modifier =
                        Modifier
                            .heightIn(
                                min = 48.dp,
                            ).testTag("settings:confirm"),
                ) { Text(stringResource(R.string.settings_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirm = false },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("settings:cancel"),
                ) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
    if (confirmProgress) {
        AlertDialog(
            onDismissRequest = { confirmProgress = false },
            title = { Text(stringResource(R.string.settings_reset_progress)) },
            text = { Text(stringResource(R.string.settings_reset_progress_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetProgress()
                        confirmProgress = false
                    },
                    modifier =
                        Modifier
                            .heightIn(min = 48.dp)
                            .testTag("settings:resetProgress:confirm"),
                ) {
                    Text(stringResource(R.string.settings_reset_progress_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmProgress = false },
                    modifier =
                        Modifier
                            .heightIn(min = 48.dp)
                            .testTag("settings:resetProgress:cancel"),
                ) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/** One option of a mutually exclusive group; the whole row, label included, selects it. */
@Composable
private fun SettingRadio(
    labelId: Int,
    tag: String,
    selected: Boolean,
    select: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = select)
            .testTag("settings:$tag"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(labelId))
    }
}

/** An on/off setting; the whole row, label included, toggles it. */
@Composable
private fun SettingSwitch(
    labelId: Int,
    tag: String,
    value: Boolean,
    change: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = value, role = Role.Switch, onValueChange = change)
            .testTag("settings:$tag"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(labelId), Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = null)
    }
}
