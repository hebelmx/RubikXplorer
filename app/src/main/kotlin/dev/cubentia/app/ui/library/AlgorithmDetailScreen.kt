package dev.cubentia.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalRenderStyle
import dev.cubentia.app.ui.lesson.DemonstrationStage
import dev.cubentia.app.ui.lesson.NotationChip
import dev.cubentia.app.ui.lesson.rememberDemonstrationPlayerState
import dev.cubentia.app.ui.playback.PlaybackFrameLoop
import dev.cubentia.app.ui.playback.PlaybackTransport
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.content.Algorithm
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.Case
import dev.cubentia.domain.lastlayer.RecognitionDiagram
import dev.cubentia.domain.lastlayer.RecognitionDiagramResult
import dev.cubentia.domain.playback.Demonstration
import dev.cubentia.domain.playback.DemonstrationResult
import dev.cubentia.domain.playback.MoveNote
import dev.cubentia.domain.text.Text as ContentText

/** Narrowest pane that fits the transport's row of five buttons, at the largest font scale. */
private val MinLandscapePaneWidth = 320.dp

/** Test-only marker of the strip's current Move; the merged card would otherwise misreport a `selected` state. */
internal val CurrentMoveKey = SemanticsPropertyKey<Boolean>("CurrentMove")

/**
 * The Learner's latest choice on one open detail screen, so a tap made while writes are queued is judged against it.
 * Once the latest write fails, the persisted status shows again.
 */
internal class RequestedAlgorithmStatus {
    private var latest by mutableStateOf<AlgorithmStatus?>(null)
    private var queued by mutableIntStateOf(0)

    fun shown(persisted: AlgorithmStatus): AlgorithmStatus = latest ?: persisted

    fun request(status: AlgorithmStatus) {
        latest = status
        queued++
    }

    /** The writer is FIFO, so the write that empties the queue is the latest one. */
    fun settled(written: Boolean) {
        queued--
        if (!written && queued == 0) latest = null
    }
}

/** A Library Demonstration is ephemeral and never enters the Learner's session. */
@Composable
fun AlgorithmDetailScreen(
    case: Case,
    algorithm: Algorithm,
    strings: ContentStrings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showDiagram: Boolean = RECOGNITION_DIAGRAMS_ENABLED,
    status: AlgorithmStatus = AlgorithmStatus.NOT_STARTED,
    onSetStatus: (AlgorithmStatus) -> Unit = {},
    autoPlay: Boolean = false,
    onPractice: ((fromSetUp: Boolean) -> Unit)? = null,
    pausesLesson: Boolean = false,
) {
    val result = remember(algorithm) { Demonstration.ofCase(algorithm) }
    val tokens = RubikTheme.tokens
    val style = LocalRenderStyle.current
    val name = strings.resolve(case.name)
    val title: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("algorithmDetail:back")) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.library_detail_back), tint = tokens.inkPrimary)
            }
            Column {
                Text(
                    name,
                    Modifier.testTag("algorithmDetail:title").semantics {
                        heading()
                    },
                    color = tokens.inkPrimary,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(familyHeading(strings, case.set, case.family), color = tokens.inkSecondary)
            }
        }
    }
    if (result !is DemonstrationResult.Ready) {
        Column(modifier.padding(16.dp)) {
            title()
            Text(stringResource(R.string.library_error), color = tokens.inkPrimary)
        }
        return
    }
    val state = rememberDemonstrationPlayerState(result.demonstration)
    var choosingPractice by rememberSaveable(algorithm.id.value) { mutableStateOf(false) }
    if (choosingPractice && onPractice != null) {
        PracticeChoiceSheet(
            strings,
            pausesLesson = pausesLesson,
            onChoose = { fromSetUp ->
                choosingPractice = false
                state.pause()
                onPractice(fromSetUp)
            },
            onDismiss = { choosingPractice = false },
        )
    }
    LaunchedEffect(algorithm.id) { if (autoPlay) state.play() }
    PlaybackFrameLoop(state)
    val controls: @Composable () -> Unit = {
        PlaybackTransport(state, strings, note = state.currentMove?.let { strings.resolve(MoveNote.of(it)) }, onScrub = state::scrubTo)
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    tokens.surfaceRaised,
                    MaterialTheme.shapes.medium,
                ).padding(12.dp)
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val derived = remember(algorithm, showDiagram) { if (showDiagram) RecognitionDiagram.derive(algorithm) else null }
            if (derived is RecognitionDiagramResult.Derived) {
                RecognitionDiagramView(
                    derived.diagram,
                    strings.resolve(
                        ContentText(
                            "msg.library.diagram.description",
                            mapOf("caseName" to name),
                        ),
                    ),
                    style,
                )
            }
            // Notation reads left to right in every locale: mirroring it would reorder the Algorithm.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                FlowRow(
                    Modifier.testTag("algorithmDetail:strip"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    result.demonstration.moves.forEachIndexed { index, move ->
                        val current = index == state.shownMoveIndex
                        Box(Modifier.testTag("algorithmDetail:move:$index").semantics { this[CurrentMoveKey] = current }) {
                            NotationChip(move, current)
                        }
                    }
                }
            }
        }
        if (onPractice != null) {
            Button(
                onClick = { choosingPractice = true },
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = tokens.onAccent),
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchMin).testTag("algorithmDetail:practice"),
            ) {
                Text(strings.resolve(ContentText("btn.library.practice")))
            }
        }
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AlgorithmStatus.entries.forEach { option ->
                LibraryChip(
                    strings.resolve(ContentText("msg.library.status.${option.name.lowercase().replace('_', '-')}")),
                    status == option,
                    "algorithmDetail:status:${option.name}",
                ) {
                    if (option != status) onSetStatus(option)
                }
            }
        }
        if (status == AlgorithmStatus.NOT_STARTED) {
            Text(
                strings.resolve(ContentText("msg.library.status.not-in-set")),
                Modifier.testTag("algorithmDetail:status-line"),
                color = tokens.inkSecondary,
            )
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().padding(8.dp)) {
        val portraitControlsHeight = maxHeight * 0.55f
        if (maxHeight >= maxWidth || maxWidth / 2 < MinLandscapePaneWidth) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                title()
                Column(
                    Modifier.weight(1f).semantics { isTraversalGroup = true },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DemonstrationStage(state, strings, style, null, null, showFrameLabel = true, freeViewRotation = true)
                }
                Column(Modifier.heightIn(max = portraitControlsHeight).verticalScroll(rememberScrollState())) { controls() }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).semantics { isTraversalGroup = true },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    title()
                    DemonstrationStage(state, strings, style, null, null, showFrameLabel = true, freeViewRotation = true)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { controls() }
            }
        }
    }
}

/**
 * The Algorithm practice choice (UX Flow 11): set the Case up from solved, or start at the Algorithm because the
 * cube already shows the Case. Each option closes the sheet first; back or the scrim calls [onDismiss] and starts
 * nothing. When [pausesLesson], it says that starting a practice pauses the live Lesson or practice in Learn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PracticeChoiceSheet(
    strings: ContentStrings,
    onChoose: (fromSetUp: Boolean) -> Unit,
    onDismiss: () -> Unit,
    pausesLesson: Boolean = false,
) {
    val tokens = RubikTheme.tokens

    fun text(key: String) = strings.resolve(ContentText(key))
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tokens.surfaceRaised,
        contentColor = tokens.inkPrimary,
        // Back and the scrim dismiss the sheet; Material's 32dp drag handle would be a sub-48dp target.
        dragHandle = null,
        modifier = Modifier.testTag("algorithmDetail:practiceChoice"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
            verticalArrangement = Arrangement.spacedBy(Spacing.s2),
        ) {
            Text(
                text("msg.library.practice.question"),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.inkPrimary,
                modifier = Modifier.semantics { heading() }.testTag("algorithmDetail:practiceChoice:title"),
            )
            if (pausesLesson) {
                Text(
                    text("msg.library.practice.pauses-session"),
                    color = tokens.inkSecondary,
                    modifier = Modifier.testTag("algorithmDetail:practiceChoice:pauseNote"),
                )
            }
            TextButton(
                onClick = { onChoose(true) },
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchMin).testTag("algorithmDetail:practice:setUp"),
            ) {
                Text(text("btn.library.practice.set-up"), modifier = Modifier.fillMaxWidth())
            }
            TextButton(
                onClick = { onChoose(false) },
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchMin).testTag("algorithmDetail:practice:alreadyCase"),
            ) {
                Text(text("btn.library.practice.already-case"), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
