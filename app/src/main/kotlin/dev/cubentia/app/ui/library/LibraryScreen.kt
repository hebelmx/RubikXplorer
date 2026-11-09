package dev.cubentia.app.ui.library

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cubentia.app.R
import dev.cubentia.app.content.CatalogState
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.shell.WindowWidthClass
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmLibrary
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.CaseSet
import dev.cubentia.domain.content.FamilyId
import dev.cubentia.domain.content.LibraryFilter
import dev.cubentia.domain.text.Text as ContentText

internal const val SELECTED_ICON_TAG = "library:selected-icon"

val LibraryFilterSaver =
    listSaver<LibraryFilter, String>(
        save = { listOf(it.family?.value.orEmpty(), it.status?.name.orEmpty()) },
        restore = {
            LibraryFilter(
                it[0].takeIf(String::isNotEmpty)?.let(::FamilyId),
                it[1].takeIf(String::isNotEmpty)?.let(AlgorithmStatus::valueOf),
            )
        },
    )

/**
 * The Library tab (FR-18): the Algorithm tiles under OLL and PLL family headings, with neutral filter
 * chips for family and Algorithm Status. [widthClass] is the window's class, not the width beside the
 * rail. [statuses] holds the Learner's own Algorithm Statuses; an absent Algorithm is *Not started*.
 * A filter that matches nothing offers [onOpenTwoLook].
 */
@Composable
fun LibraryScreen(
    catalog: CatalogState,
    widthClass: WindowWidthClass,
    filter: LibraryFilter,
    onFilterChange: (LibraryFilter) -> Unit,
    gridState: LazyGridState,
    onOpenTwoLook: () -> Unit,
    modifier: Modifier = Modifier,
    statuses: Map<AlgorithmId, AlgorithmStatus> = emptyMap(),
    familyChipsScroll: ScrollState = rememberScrollState(),
    onOpenAlgorithm: (AlgorithmId) -> Unit = {},
) {
    val ink = RubikTheme.tokens.inkPrimary
    when (catalog) {
        CatalogState.Loading -> {
            Unit
        }

        is CatalogState.Failed -> {
            Text(stringResource(R.string.library_error), modifier, color = ink)
        }

        is CatalogState.Ready -> {
            val strings = catalog.strings

            fun text(key: String) = strings.resolve(ContentText(key))
            val sections = AlgorithmLibrary.sections(catalog.bundle.cases, catalog.bundle.algorithms, filter, statuses)
            Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.nav_library),
                    modifier = Modifier.semantics { heading() },
                    color = ink,
                    style = MaterialTheme.typography.headlineLarge,
                )
                Row(Modifier.horizontalScroll(familyChipsScroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AlgorithmLibrary.families(catalog.bundle.cases).forEach { family ->
                        LibraryChip(text("msg.library.family.${family.value}"), filter.family == family, "library:family:${family.value}") {
                            onFilterChange(filter.copy(family = family.takeUnless { filter.family == it }))
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AlgorithmStatus.entries.forEach { status ->
                        LibraryChip(
                            text("msg.library.status.${status.name.lowercase().replace('_', '-')}"),
                            filter.status == status,
                            "library:status:${status.name}",
                        ) {
                            onFilterChange(filter.copy(status = status.takeUnless { filter.status == it }))
                        }
                    }
                }
                if (sections.isEmpty()) {
                    Text(text("msg.library.filter-empty"), color = ink)
                    TextButton(onClick = onOpenTwoLook, modifier = Modifier.testTag("library:two-look")) {
                        Text(text("btn.library.open-two-look"))
                    }
                } else {
                    val columns =
                        when (widthClass) {
                            WindowWidthClass.COMPACT -> 2
                            WindowWidthClass.MEDIUM -> 3
                            WindowWidthClass.EXPANDED -> 4
                        }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = gridState,
                        modifier = Modifier.weight(1f).testTag("library:grid"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sections.forEach { section ->
                            item(key = "heading:${section.set}:${section.family}", span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    familyHeading(strings, section.set, section.family),
                                    modifier = Modifier.semantics { heading() },
                                    color = ink,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            items(section.entries, key = { it.case.id.value }) { entry ->
                                AlgorithmTile(
                                    entry.case,
                                    entry.algorithm,
                                    strings,
                                    Modifier
                                        .fillMaxWidth()
                                        .testTag(
                                            "library:case:${entry.case.id.value}",
                                        ).clip(
                                            MaterialTheme.shapes.medium,
                                        ).clickable(role = Role.Button, onClickLabel = stringResource(R.string.library_open_detail)) {
                                            onOpenAlgorithm(entry.algorithm.id)
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryChip(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.testTag(tag),
        leadingIcon =
            if (selected) {
                {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize).testTag(SELECTED_ICON_TAG),
                    )
                }
            } else {
                null
            },
        colors =
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = RubikTheme.tokens.surfaceRaised,
                selectedLabelColor = RubikTheme.tokens.inkPrimary,
                selectedLeadingIconColor = RubikTheme.tokens.inkPrimary,
            ),
    )
}

internal fun familyHeading(
    strings: ContentStrings,
    set: CaseSet,
    family: FamilyId,
): String =
    strings.resolve(
        ContentText(
            "msg.library.family.heading",
            mapOf(
                "set" to strings.resolve(ContentText("msg.library.set.${set.name.lowercase()}")),
                "family" to strings.resolve(ContentText("msg.library.family.${family.value}")),
            ),
        ),
    )
