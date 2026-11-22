package dev.cubentia.app.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.cubentia.app.R
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.beta.BetaLabel
import dev.cubentia.app.ui.beta.BetaNote
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.application.usecase.EntryLevel
import dev.cubentia.domain.text.Text as ContentText

/**
 * The entry-level picker "Where do you want to start?" (FR-4, UX-DR22, UX-DR25, UX Flow 8): a full-screen task flow
 * reached from Welcome's *I can already solve it* or Learn's *Find my place* → *Choose my level*. Three levels are
 * recorded by [onChoose] (disabled while [saving]); [onCheck] opens the whole-Course skip-ahead check (Story 7.7);
 * [onScan], present only while `PLACEMENT` is above OFF (AD-18), runs the Placement flow. Copy never depends on the
 * Learner's progress. Back, from the top bar or the system, calls [onBack] and writes nothing.
 */
@Composable
internal fun EntryLevelScreen(
    strings: ContentStrings,
    onChoose: (EntryLevel) -> Unit,
    onCheck: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onScan: (() -> Unit)? = null,
    scanBeta: Boolean = false,
    saving: Boolean = false,
    saveFailed: Boolean = false,
) {
    val tokens = RubikTheme.tokens

    fun text(key: String) = strings.resolve(ContentText(key))
    BackHandler(onBack = onBack)
    Column(
        modifier
            .fillMaxSize()
            .background(tokens.surfaceBase)
            .safeDrawingPadding()
            .testTag("entryLevel"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.touchMin)
                    .padding(horizontal = Spacing.s2, vertical = Spacing.s2),
        ) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(contentColor = tokens.inkPrimary),
                modifier = Modifier.size(Spacing.touchMin).testTag("entryLevel:back"),
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = text("btn.solve.close"))
            }
            Text(
                text("msg.entry-level.title"),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.inkPrimary,
                modifier = Modifier.weight(1f).semantics { heading() }.testTag("entryLevel:title"),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            listOf(
                Triple(EntryLevel.FIRST_TWO_LAYERS, "btn.entry-level.first-two-layers", "entryLevel:firstTwoLayers"),
                Triple(EntryLevel.BEGINNER_METHOD, "btn.entry-level.beginner-method", "entryLevel:beginner"),
                Triple(EntryLevel.TWO_LOOK, "btn.entry-level.two-look", "entryLevel:twoLook"),
            ).forEach { (level, key, tag) ->
                OutlinedButton(
                    onClick = { onChoose(level) },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchMin).testTag(tag),
                ) {
                    Text(text(key))
                }
            }
            TextButton(
                onClick = onCheck,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchMin).testTag("entryLevel:check"),
            ) {
                Text(text("btn.entry-level.check"))
            }
            Text(
                text("msg.entry-level.note"),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.inkSecondary,
                modifier = Modifier.testTag("entryLevel:note"),
            )
            if (saveFailed) {
                Text(
                    text("msg.entry-level.save-failed"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.inkPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("entryLevel:error"),
                )
            }
            if (onScan != null) {
                val label = text("btn.entry-level.scan")
                val betaLabel = if (scanBeta) text("msg.feature.beta") else null
                TextButton(
                    onClick = onScan,
                    enabled = !saving,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.touchMin)
                            .testTag("entryLevel:scan")
                            .semantics { if (betaLabel != null) contentDescription = "$label, $betaLabel" },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s1), verticalAlignment = Alignment.CenterVertically) {
                        Text(label)
                        if (betaLabel != null) {
                            BetaLabel(
                                betaLabel,
                                LocalContentColor.current,
                                MaterialTheme.typography.labelMedium,
                                Modifier.testTag("entryLevel:scan:beta"),
                            )
                        }
                    }
                }
                if (scanBeta) {
                    BetaNote(text("msg.feature.camera.beta-note"), Modifier.testTag("entryLevel:scan:betaNote"))
                }
            }
        }
    }
}

/**
 * The Learn tab's *Find my place* sheet (FR-4, UX Flow 8): *Scan my cube* (only when [onScan] is present, i.e.
 * `PLACEMENT` above OFF, AD-18) and *Choose my level*. Neither action writes progress; each closes the sheet first.
 * Back or the scrim calls [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FindMyPlaceSheet(
    strings: ContentStrings,
    onChooseLevel: () -> Unit,
    onDismiss: () -> Unit,
    onScan: (() -> Unit)? = null,
    scanBeta: Boolean = false,
) {
    val tokens = RubikTheme.tokens

    fun text(key: String) = strings.resolve(ContentText(key))
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tokens.surfaceRaised,
        contentColor = tokens.inkPrimary,
        // Back and the scrim dismiss the sheet; Material's 32dp drag handle would be a sub-48dp target (UX-DR25).
        dragHandle = null,
        modifier = Modifier.testTag("findMyPlace"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
            verticalArrangement = Arrangement.spacedBy(Spacing.s2),
        ) {
            Text(
                text("msg.find-my-place.title"),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.inkPrimary,
                modifier = Modifier.semantics { heading() }.testTag("findMyPlace:title"),
            )
            if (onScan != null) {
                val label = text("btn.find-my-place.scan")
                val betaLabel = if (scanBeta) text("msg.feature.beta") else null
                TextButton(
                    onClick = {
                        onDismiss()
                        onScan()
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.touchMin)
                            .testTag("findMyPlace:scan")
                            .semantics { if (betaLabel != null) contentDescription = "$label, $betaLabel" },
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s1),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label)
                        if (betaLabel != null) {
                            BetaLabel(
                                betaLabel,
                                LocalContentColor.current,
                                MaterialTheme.typography.labelMedium,
                                Modifier.testTag("findMyPlace:scan:beta"),
                            )
                        }
                    }
                }
                if (scanBeta) {
                    BetaNote(text("msg.feature.camera.beta-note"), Modifier.testTag("findMyPlace:scan:betaNote"))
                }
            }
            TextButton(
                onClick = {
                    onDismiss()
                    onChooseLevel()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchMin).testTag("findMyPlace:chooseLevel"),
            ) {
                Text(text("btn.find-my-place.choose-level"), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
