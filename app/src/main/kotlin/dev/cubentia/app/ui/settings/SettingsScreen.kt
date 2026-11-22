package dev.cubentia.app.ui.settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.preferences.LocalPreferenceStore
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.application.preference.AnimationSpeed
import dev.cubentia.application.preference.Preference
import dev.cubentia.application.preference.ThemeChoice
import kotlinx.coroutines.launch
import dev.cubentia.domain.text.Text as ContentText

class CalibrationSettings(
    val onCalibrate: () -> Unit,
    val onReset: suspend () -> Boolean,
)

@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onResetProgress: suspend () -> Boolean = { true },
    store: PreferenceStore = LocalPreferenceStore.current,
    calibration: CalibrationSettings? = null,
    // Null while content loads: content-text rows (Move pictures, calibration) wait for it.
    strings: dev.cubentia.app.content.ContentStrings? = null,
) {
    val prefs by store.preferences.collectAsState()
    var confirm by rememberSaveable { mutableStateOf(false) }
    var confirmProgress by rememberSaveable { mutableStateOf(false) }
    var resetFailed by rememberSaveable { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    val tokens = RubikTheme.tokens
    // Labels start where the Close settings link text starts (#275): the link's inset is its TextButton content padding.
    val inset = labelInset(LocalLayoutDirection.current)
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
            Text(stringResource(R.string.settings_title), Modifier.padding(inset).testTag("settings:title"))
            Text(stringResource(R.string.settings_theme), Modifier.padding(inset).testTag("settings:themeHeading"))
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
                        inset,
                    ) { store.update(Preference.Theme(choice)) }
                }
            }
            SettingSwitch(
                stringResource(R.string.settings_glyphs),
                "glyphs",
                prefs.colorBlindGlyphs,
                inset,
            ) { store.update(Preference.ColorBlindGlyphs(it)) }
            if (strings != null) {
                val movePicturesLabel = strings.resolve(ContentText("btn.settings.move-pictures"))
                SettingSwitch(movePicturesLabel, "movePictures", prefs.movePictures, inset) { store.update(Preference.MovePictures(it)) }
            }
            Text(stringResource(R.string.settings_speed), Modifier.padding(inset).testTag("settings:speedHeading"))
            Column(Modifier.selectableGroup()) {
                AnimationSpeed.entries.forEach { speed ->
                    val label =
                        when (speed) {
                            AnimationSpeed.SLOW -> R.string.settings_slow
                            AnimationSpeed.NORMAL -> R.string.settings_normal
                            AnimationSpeed.FAST -> R.string.settings_fast
                        }
                    SettingRadio(label, "speed:${speed.name.lowercase()}", prefs.animationSpeed == speed, inset) {
                        store.update(Preference.Speed(speed))
                    }
                }
            }
            SettingSwitch(
                stringResource(R.string.free_cube_haptics),
                "haptics",
                prefs.haptics,
                inset,
            ) { store.update(Preference.Haptics(it)) }
            SettingSwitch(stringResource(R.string.settings_reduce_motion), "reduceMotion", prefs.reduceMotion, inset) {
                store.update(Preference.ReduceMotion(it))
            }
            TextButton(onClick = {
                confirm = true
            }, modifier = Modifier.heightIn(min = 48.dp).testTag("settings:reset")) { Text(stringResource(R.string.settings_reset)) }
            TextButton(onClick = {
                confirmProgress = true
            }, modifier = Modifier.heightIn(min = 48.dp).testTag("settings:resetProgress")) {
                Text(stringResource(R.string.settings_reset_progress))
            }
            if (calibration != null && strings != null) CalibrationRows(calibration, strings)
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
            onDismissRequest = {
                confirmProgress = false
                resetFailed = false
            },
            title = { Text(stringResource(R.string.settings_reset_progress)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_reset_progress_description))
                    if (resetFailed) {
                        Text(
                            stringResource(R.string.settings_reset_progress_failed),
                            Modifier.testTag("settings:resetProgress:error"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetting = true
                        scope.launch {
                            // Keep the dialog open on failure so the Learner can retry; progress was not erased.
                            val reset = onResetProgress()
                            resetting = false
                            resetFailed = !reset
                            if (reset) confirmProgress = false
                        }
                    },
                    enabled = !resetting,
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
                    onClick = {
                        confirmProgress = false
                        resetFailed = false
                    },
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

/** Horizontal-only inset matching the text inside a [TextButton], so plain labels line up with link text. */
private fun labelInset(direction: LayoutDirection): PaddingValues {
    val button = ButtonDefaults.TextButtonContentPadding
    return PaddingValues(start = button.calculateStartPadding(direction), end = button.calculateEndPadding(direction))
}

/** One option of a mutually exclusive group; the whole row, label included, selects it. */
@Composable
private fun SettingRadio(
    labelId: Int,
    tag: String,
    selected: Boolean,
    inset: PaddingValues,
    select: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = select)
            .testTag("settings:$tag")
            .padding(inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.testTag("settings:$tag:radio"))
        Text(stringResource(labelId))
    }
}

/** An on/off setting; the whole row, label included, toggles it. */
@Composable
private fun SettingSwitch(
    label: String,
    tag: String,
    value: Boolean,
    inset: PaddingValues,
    change: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = value, role = Role.Switch, onValueChange = change)
            .testTag("settings:$tag")
            .padding(inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = null)
    }
}

@Composable
private fun CalibrationRows(
    settings: CalibrationSettings,
    strings: dev.cubentia.app.content.ContentStrings,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun text(key: String) =
        strings.resolve(
            dev.cubentia.domain.text
                .Text(key),
        )
    TextButton(settings.onCalibrate, Modifier.heightIn(min = 48.dp).testTag("settings:calibration")) {
        Text(text("btn.settings.calibration"))
    }
    TextButton({
        confirm = true
        failed = false
    }, Modifier.heightIn(min = 48.dp).testTag("settings:calibration:reset")) {
        Text(text("btn.settings.calibration-reset"))
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirm = false },
            title = { Text(text("btn.settings.calibration-reset")) },
            text = {
                Column {
                    Text(text("msg.settings.calibration-reset"))
                    if (failed) Text(text("msg.settings.calibration-reset-failed"), Modifier.testTag("settings:calibration:error"))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = settings.onReset()
                            busy = false
                            failed = !result
                            if (result) confirm = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("settings:calibration:confirm"),
                ) { Text(text("btn.settings.calibration-reset")) }
            },
            dismissButton = {
                TextButton({ confirm = false }, enabled = !busy, modifier = Modifier.testTag("settings:calibration:cancel")) {
                    Text(text("btn.scan.cancel"))
                }
            },
        )
    }
}
