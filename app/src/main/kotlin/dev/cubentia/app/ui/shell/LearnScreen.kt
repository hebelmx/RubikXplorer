package dev.cubentia.app.ui.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.content.CatalogEntry
import dev.cubentia.app.content.CatalogState
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.app.ui.trend.TrendRow
import dev.cubentia.application.session.AttemptRecord
import dev.cubentia.domain.content.Lesson
import dev.cubentia.domain.content.LessonId
import dev.cubentia.domain.content.Stage
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.tutor.Mastery
import dev.cubentia.domain.tutor.MasteryAttempt
import dev.cubentia.domain.tutor.ProgressEvent
import dev.cubentia.domain.tutor.StageProgress
import dev.cubentia.domain.tutor.StageStatus
import dev.cubentia.domain.tutor.StageTrendCalculator

/**
 * The Learn tab (FR-4, FR-17, FR-20, AD-10, AD-17, UX-DR18): shows the [continueEntry] card when a
 * Lesson is resumable, the seven Course Stage rows with 1-based number in `data`, title in `title`,
 * and derived [StageStatus] text + icon, and a *Faster last layer* placeholder row hidden until
 * Epic 9 ([showFasterLastLayer]). Tapping a Stage row opens [StageDetailScreen] while keeping the
 * navigation shell's bar/rail visible.
 */
@Composable
fun LearnScreen(
    catalog: CatalogState,
    onOpen: (LessonId) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    continueEntry: CatalogEntry? = null,
    progressEvents: List<ProgressEvent> = emptyList(),
    masteredStages: Set<StageId> = emptySet(),
    selectedStage: StageId? = null,
    onSelectStage: (StageId?) -> Unit = {},
    showFasterLastLayer: Boolean = false,
    showTwoLookLocked: Boolean = false,
    onCloseTwoLookLocked: () -> Unit = {},
    practiceEnabled: Boolean = false,
    onPracticeStage: (StageId) -> Unit = {},
    skipCheckEnabled: Boolean = true,
    onSkipCheck: (StageId) -> Unit = {},
    solvesCompleted: Int = 0,
    solvesRequired: Int = DEFAULT_MASTERY_SOLVES_REQUIRED,
    reducedHelpSolves: Int = 0,
    reducedHelpRequired: Int = DEFAULT_MASTERY_REDUCED_REQUIRED,
    attemptRecords: List<MasteryAttempt> = emptyList(),
    abandonedSkipCheckStage: StageId? = null,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(RubikTheme.tokens.surfaceBase)
                .testTag("learn"),
    ) {
        when (catalog) {
            // Nothing to show until the content arrives; loading is quick and never fails silently.
            CatalogState.Loading -> {}

            is CatalogState.Failed -> {
                Message(R.string.learn_error, "learn:error")
            }

            is CatalogState.Ready -> {
                val courseStages = catalog.bundle.stages
                val stages = courseStages + if (showFasterLastLayer) catalog.bundle.twoLookStages else emptyList()
                val stageIndex = selectedStage?.let { id -> stages.indexOfFirst { it.id == id } } ?: -1
                val activeStage = stages.getOrNull(stageIndex)
                val effectiveMasteredStages =
                    remember(masteredStages, courseStages, attemptRecords, progressEvents, catalog.bundle.tutorConfig.mastery) {
                        masteredStages +
                            Mastery.masteredStages(courseStages, attemptRecords, progressEvents, catalog.bundle.tutorConfig.mastery)
                    }
                if (activeStage != null) {
                    val twoLook = activeStage in catalog.bundle.twoLookStages
                    val status =
                        if (twoLook) {
                            StageProgress.status(activeStage, listOf(activeStage), progressEvents)
                        } else {
                            StageProgress.status(
                                activeStage,
                                courseStages,
                                progressEvents,
                                effectiveMasteredStages,
                            )
                        }
                    val isPracticeEnabled =
                        activeStage.id != StageId("stage-01-fundamentals") && status != StageStatus.LOCKED
                    val masteryProgress =
                        Mastery.evaluate(
                            activeStage,
                            attemptRecords,
                            progressEvents,
                            catalog.bundle.tutorConfig.mastery,
                        )
                    val effectiveSolvesCompleted =
                        solvesCompleted.takeIf { attemptRecords.isEmpty() && it != 0 } ?: masteryProgress.solvesCompleted
                    val effectiveSolvesRequired =
                        if (solvesRequired != DEFAULT_MASTERY_SOLVES_REQUIRED) solvesRequired else masteryProgress.solvesRequired
                    val effectiveReducedSolves =
                        reducedHelpSolves.takeIf { attemptRecords.isEmpty() && it != 0 } ?: masteryProgress.reducedHelpSolves
                    val effectiveReducedRequired =
                        if (reducedHelpRequired !=
                            DEFAULT_MASTERY_REDUCED_REQUIRED
                        ) {
                            reducedHelpRequired
                        } else {
                            masteryProgress.reducedHelpRequired
                        }
                    val isSkipCheckEnabled =
                        skipCheckEnabled && (status == StageStatus.LOCKED || status == StageStatus.IN_PROGRESS)
                    StageDetailScreen(
                        stage = activeStage,
                        stageNumber = if (twoLook) catalog.bundle.twoLookStages.indexOf(activeStage) + 1 else stageIndex + 1,
                        courseControls = !twoLook,
                        status = status,
                        stages = stages,
                        strings = catalog.strings,
                        progressEvents = progressEvents,
                        attemptRecords = attemptRecords,
                        masteredStages = effectiveMasteredStages,
                        onBack = { onSelectStage(null) },
                        onOpen = onOpen,
                        practiceEnabled = isPracticeEnabled,
                        onPracticeStage = { onPracticeStage(activeStage.id) },
                        skipCheckEnabled = isSkipCheckEnabled,
                        onSkipCheck = { onSkipCheck(activeStage.id) },
                        solvesCompleted = effectiveSolvesCompleted,
                        solvesRequired = effectiveSolvesRequired,
                        reducedHelpSolves = effectiveReducedSolves,
                        reducedHelpRequired = effectiveReducedRequired,
                        abandonedSkipCheckMessage =
                            if (abandonedSkipCheckStage == activeStage.id) {
                                stringResource(R.string.stage_skip_check_abandoned)
                            } else {
                                null
                            },
                    )
                } else if (showTwoLookLocked) {
                    TwoLookLockedContent(onBack = onCloseTwoLookLocked)
                } else {
                    StageListContent(
                        catalog = catalog,
                        continueEntry = continueEntry,
                        progressEvents = progressEvents,
                        masteredStages = effectiveMasteredStages,
                        scrollState = scrollState,
                        showFasterLastLayer = showFasterLastLayer,
                        onOpen = onOpen,
                        onSelectStage = { onSelectStage(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StageListContent(
    catalog: CatalogState.Ready,
    continueEntry: CatalogEntry?,
    progressEvents: List<ProgressEvent>,
    masteredStages: Set<StageId>,
    scrollState: ScrollState,
    showFasterLastLayer: Boolean,
    onOpen: (LessonId) -> Unit,
    onSelectStage: (StageId) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
    ) {
        if (catalog.bundle.stages.isEmpty() || catalog.lessons.isEmpty()) {
            Message(R.string.learn_empty, "learn:empty")
        } else {
            continueEntry?.let { entry ->
                Column(
                    modifier =
                        Modifier
                            .padding(Spacing.s4)
                            .fillMaxWidth()
                            .heightIn(min = Spacing.touchMin)
                            .clip(MaterialTheme.shapes.medium)
                            .background(RubikTheme.tokens.surfaceRaised)
                            .clickable(role = Role.Button) { onOpen(entry.lesson.id) }
                            .testTag("learn:continue")
                            .padding(Spacing.s4),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s1),
                ) {
                    Text(stringResource(R.string.learn_continue), color = RubikTheme.tokens.inkPrimary)
                    Text(catalog.strings.resolve(entry.lesson.objective), color = RubikTheme.tokens.inkPrimary)
                    Text(catalog.strings.resolve(entry.stage.title), color = RubikTheme.tokens.inkSecondary)
                }
            }
            val stages = catalog.bundle.stages
            val statuses = StageProgress.statuses(stages, progressEvents, masteredStages)
            stages.forEachIndexed { index, stage ->
                StageRow(
                    stage = stage,
                    number = index + 1,
                    status = statuses[stage.id] ?: StageStatus.LOCKED,
                    strings = catalog.strings,
                    onSelect = { onSelectStage(stage.id) },
                )
                HorizontalDivider(color = RubikTheme.tokens.hairline)
            }
            if (showFasterLastLayer) {
                FasterLastLayerRow()
                catalog.bundle.twoLookStages.forEachIndexed { index, stage ->
                    StageRow(
                        stage,
                        index + 1,
                        StageProgress.status(stage, listOf(stage), progressEvents),
                        catalog.strings,
                        { onSelectStage(stage.id) },
                    )
                }
                HorizontalDivider(color = RubikTheme.tokens.hairline)
            }
        }
    }
}

@Composable
private fun StageRow(
    stage: Stage,
    number: Int,
    status: StageStatus,
    strings: ContentStrings,
    onSelect: () -> Unit,
) {
    val tokens = RubikTheme.tokens
    val locked = status == StageStatus.LOCKED
    val numberText = number.toString()
    val statusLabel = stringResource(statusTextRes(status))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchMin)
                .clickable(role = Role.Button, onClick = onSelect)
                .semantics {
                    stateDescription = statusLabel
                    this[SemanticsProperties.Text] =
                        listOf(AnnotatedString(numberText), AnnotatedString(statusLabel))
                }.testTag("learn:stage:${stage.id}")
                .padding(horizontal = Spacing.s4, vertical = Spacing.s3),
    ) {
        Text(
            text = numberText,
            style = RubikTheme.textStyles.data,
            color = if (locked) tokens.inkDisabled else tokens.inkSecondary,
            modifier =
                Modifier
                    .semantics(mergeDescendants = true) {}
                    .testTag("learn:stage:number:${stage.id}"),
        )
        Text(
            text = strings.resolve(stage.title),
            style = MaterialTheme.typography.titleLarge,
            color = if (locked) tokens.inkDisabled else tokens.inkPrimary,
            modifier = Modifier.weight(1f),
        )
        StageStatusBadge(
            status = status,
            tag = "learn:stage:status:${stage.id}",
        )
    }
}

@Composable
private fun FasterLastLayerRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchMin)
                .semantics(mergeDescendants = true) { heading() }
                .testTag("learn:fasterLastLayer")
                .padding(horizontal = Spacing.s4, vertical = Spacing.s3),
    ) {
        Text(
            text = stringResource(R.string.learn_faster_last_layer),
            style = MaterialTheme.typography.titleLarge,
            color = RubikTheme.tokens.inkPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Where the Library's 2-look link lands before *Faster last layer* unlocks: says it is locked and what unlocks it (FR-17). */
@Composable
private fun TwoLookLockedContent(onBack: () -> Unit) {
    val tokens = RubikTheme.tokens
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .testTag("learn:twoLookLocked"),
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
                modifier = Modifier.size(Spacing.touchMin).testTag("learn:twoLookLocked:back"),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.stage_detail_back),
                )
            }
            Text(
                text = stringResource(R.string.learn_faster_last_layer),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.inkPrimary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            StageStatusBadge(status = StageStatus.LOCKED, tag = "learn:twoLookLocked:status")
        }
        Text(
            text = stringResource(R.string.learn_two_look_locked_prerequisite),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.inkSecondary,
            modifier =
                Modifier
                    .padding(horizontal = Spacing.s4, vertical = Spacing.s2)
                    .testTag("learn:twoLookLocked:prerequisite"),
        )
    }
}

/**
 * Stage detail view (FR-4, FR-17, FR-20, UX-DR18, UX-DR22, UX-DR23): shows the Stage's Lessons with
 * prerequisite gating, the Mastery progress line with a two-segment visual indicator, *Practice this
 * Stage* (disabled until Story 7.4), and *I already know this* (enabled, wired in Story 7.7).
 */
@Composable
fun StageDetailScreen(
    stage: Stage,
    stageNumber: Int,
    status: StageStatus,
    stages: List<Stage>,
    strings: ContentStrings,
    progressEvents: List<ProgressEvent> = emptyList(),
    attemptRecords: List<MasteryAttempt> = emptyList(),
    masteredStages: Set<StageId> = emptySet(),
    onBack: () -> Unit,
    onOpen: (LessonId) -> Unit,
    modifier: Modifier = Modifier,
    practiceEnabled: Boolean = false,
    onPracticeStage: () -> Unit = {},
    skipCheckEnabled: Boolean = true,
    onSkipCheck: () -> Unit = {},
    solvesCompleted: Int = 0,
    solvesRequired: Int = DEFAULT_MASTERY_SOLVES_REQUIRED,
    reducedHelpSolves: Int = 0,
    reducedHelpRequired: Int = DEFAULT_MASTERY_REDUCED_REQUIRED,
    abandonedSkipCheckMessage: String? = null,
    courseControls: Boolean = true,
) {
    val tokens = RubikTheme.tokens
    val stageTrend =
        remember(stage, attemptRecords, progressEvents) {
            StageTrendCalculator.evaluate(stage, attemptRecords, progressEvents)
        }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(tokens.surfaceBase)
                .verticalScroll(rememberScrollState())
                .testTag("stageDetail"),
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
                modifier = Modifier.size(Spacing.touchMin).testTag("stageDetail:back"),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.stage_detail_back),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) { heading() }
                        .testTag("stageDetail:title"),
            ) {
                Text(
                    text = stageNumber.toString(),
                    style = RubikTheme.textStyles.data,
                    color = tokens.inkSecondary,
                )
                Text(
                    text = strings.resolve(stage.title),
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.inkPrimary,
                )
            }
            StageStatusBadge(
                status = status,
                tag = "stageDetail:status",
            )
        }
        if (courseControls) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
                verticalArrangement = Arrangement.spacedBy(Spacing.s2),
            ) {
                Text(
                    text = stringResource(R.string.stage_mastery_progress, solvesCompleted, solvesRequired, reducedHelpSolves),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary,
                    modifier = Modifier.testTag("stageDetail:mastery"),
                )
                if (abandonedSkipCheckMessage != null) {
                    Text(
                        text = abandonedSkipCheckMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.inkSecondary,
                        modifier = Modifier.testTag("stageDetail:skipCheckAbandoned"),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                ) {
                    val firstMet = solvesCompleted >= 1
                    val secondMet = solvesCompleted >= solvesRequired && reducedHelpSolves >= reducedHelpRequired
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(Spacing.s1)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(if (firstMet) tokens.accent else tokens.hairline)
                                .testTag("stageDetail:mastery:segment:1"),
                    )
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(Spacing.s1)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(if (secondMet) tokens.accent else tokens.hairline)
                                .testTag("stageDetail:mastery:segment:2"),
                    )
                }
                TrendRow(
                    attemptsCount = stageTrend.attemptsCount,
                    hintCounts = stageTrend.hintCounts,
                    label = stringResource(R.string.stage_trend_label),
                    modifier = Modifier.testTag("stageDetail:trend"),
                )
                OutlinedButton(
                    onClick = onPracticeStage,
                    enabled = practiceEnabled,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.touchMin)
                            .testTag("stageDetail:practice"),
                ) {
                    Text(stringResource(R.string.stage_practice))
                }
                TextButton(
                    onClick = onSkipCheck,
                    enabled = skipCheckEnabled,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.touchMin)
                            .testTag("stageDetail:skipCheck"),
                ) {
                    Text(stringResource(R.string.stage_skip_check))
                }
            }
        }
        HorizontalDivider(color = tokens.hairline)
        if (stage.lessons.isEmpty()) {
            Message(R.string.stage_no_lessons, "stageDetail:noLessons")
        } else {
            val epochEvents = StageProgress.currentEpochEvents(progressEvents)
            stage.lessons.forEach { lesson ->
                val unlocked = !courseControls || StageProgress.isLessonUnlocked(lesson, stages, progressEvents, masteredStages)
                val completed = epochEvents.any { it is ProgressEvent.LessonCompleted && it.lessonId == lesson.id }
                LessonRow(
                    lesson = lesson,
                    stage = stage,
                    strings = strings,
                    unlocked = unlocked,
                    completed = completed,
                    onOpen = onOpen,
                )
                HorizontalDivider(color = tokens.hairline)
            }
        }
    }
}

@Composable
internal fun StageStatusBadge(
    status: StageStatus,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val tokens = RubikTheme.tokens
    val color = if (status == StageStatus.MASTERED) tokens.accent else tokens.inkSecondary
    val statusLabel = stringResource(statusTextRes(status))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s1),
        modifier =
            modifier
                .semantics(mergeDescendants = true) {
                    stateDescription = statusLabel
                }.testTag(tag),
    ) {
        val iconRes = statusIconRes(status)
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(STATUS_ICON_SIZE),
            )
        } else {
            val symbolRes =
                when (status) {
                    StageStatus.MASTERED -> R.string.stage_icon_mastered
                    else -> R.string.stage_icon_skipped
                }
            Text(
                text = stringResource(symbolRes),
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun LessonRow(
    lesson: Lesson,
    stage: Stage,
    strings: ContentStrings,
    unlocked: Boolean,
    completed: Boolean,
    onOpen: (LessonId) -> Unit,
) {
    val tokens = RubikTheme.tokens
    val stateLabel =
        stringResource(
            when {
                completed -> R.string.stage_status_completed
                unlocked -> R.string.stage_status_in_progress
                else -> R.string.stage_status_locked
            },
        )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchMin)
                .clickable(enabled = unlocked, role = Role.Button) { onOpen(lesson.id) }
                .semantics { stateDescription = stateLabel }
                .testTag("learn:lesson:${lesson.id}")
                .padding(horizontal = Spacing.s4, vertical = Spacing.s3),
    ) {
        Icon(
            painter =
                painterResource(
                    when {
                        completed -> R.drawable.ic_check
                        unlocked -> R.drawable.ic_stage_in_progress
                        else -> R.drawable.ic_stage_locked
                    },
                ),
            contentDescription = null,
            tint = if (unlocked) tokens.inkSecondary else tokens.inkDisabled,
            modifier = Modifier.size(STATUS_ICON_SIZE),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.s1),
        ) {
            Text(
                text = strings.resolve(lesson.objective),
                style = MaterialTheme.typography.bodyLarge,
                color = if (unlocked) tokens.inkPrimary else tokens.inkDisabled,
            )
            Text(
                text = strings.resolve(stage.title),
                style = MaterialTheme.typography.bodySmall,
                color = if (unlocked) tokens.inkSecondary else tokens.inkDisabled,
            )
        }
    }
}

@StringRes
private fun statusTextRes(status: StageStatus): Int =
    when (status) {
        StageStatus.LOCKED -> R.string.stage_status_locked
        StageStatus.IN_PROGRESS -> R.string.stage_status_in_progress
        StageStatus.COMPLETED -> R.string.stage_status_completed
        StageStatus.SKIPPED -> R.string.stage_status_skipped
        StageStatus.SKIPPED_BY_PLACEMENT -> R.string.stage_status_skipped_by_placement
        StageStatus.MASTERED -> R.string.stage_status_mastered
    }

@DrawableRes
private fun statusIconRes(status: StageStatus): Int? =
    when (status) {
        StageStatus.LOCKED -> R.drawable.ic_stage_locked
        StageStatus.IN_PROGRESS -> R.drawable.ic_stage_in_progress
        StageStatus.COMPLETED -> R.drawable.ic_check
        StageStatus.SKIPPED, StageStatus.SKIPPED_BY_PLACEMENT, StageStatus.MASTERED -> null
    }

@Composable
private fun Message(
    @StringRes text: Int,
    tag: String,
) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        color = RubikTheme.tokens.inkSecondary,
        modifier = Modifier.padding(Spacing.s4).testTag(tag),
    )
}

private val STATUS_ICON_SIZE = 18.dp
internal const val DEFAULT_MASTERY_SOLVES_REQUIRED = 2
internal const val DEFAULT_MASTERY_REDUCED_REQUIRED = 1
