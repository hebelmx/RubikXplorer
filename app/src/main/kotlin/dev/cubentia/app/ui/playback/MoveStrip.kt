package dev.cubentia.app.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.lesson.rememberReduceMotion
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.playback.MovePicture
import dev.cubentia.domain.text.Text as ContentText

@Composable
fun MoveStrip(
    moves: List<Move>,
    currentIndex: Int?,
    pictures: Boolean,
    strings: ContentStrings,
    onSelect: ((Int) -> Unit)?,
) {
    if (moves.isEmpty()) return
    // Keyed on the Moves: a new list starts from its own current Move (or its first), not the old scroll position.
    val list = rememberSaveable(moves, saver = LazyListState.Saver) { LazyListState(currentIndex ?: 0) }
    val reduceMotion = rememberReduceMotion()
    LaunchedEffect(currentIndex, list, reduceMotion) {
        if (currentIndex == null) return@LaunchedEffect
        val layout = list.layoutInfo
        val visible =
            layout.visibleItemsInfo.any {
                it.index == currentIndex && it.offset >= layout.viewportStartOffset && it.offset + it.size <= layout.viewportEndOffset
            }
        if (!visible) {
            if (reduceMotion || layout.visibleItemsInfo.isEmpty()) {
                list.scrollToItem(currentIndex)
            } else {
                list.animateScrollToItem(currentIndex)
            }
        }
    }
    val tokens = RubikTheme.tokens
    val shape = MaterialTheme.shapes.small
    val currentSpoken = remember(strings) { strings.resolve(ContentText("msg.playback.move-chip-current")) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().testTag("playback:strip"),
            state = list,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
        ) {
            itemsIndexed(moves) { index, move ->
                val current = index == currentIndex
                val notation = Notation.format(move)
                val spoken =
                    remember(strings, index, moves.size, notation) {
                        strings.resolve(
                            ContentText(
                                "msg.playback.move-chip",
                                mapOf("index" to index + 1, "total" to moves.size, "notation" to notation),
                            ),
                        )
                    }
                val touch = if (onSelect == null) Modifier else Modifier.clickable { onSelect(index) }
                Column(
                    modifier =
                        Modifier
                            .testTag("playback:chip:$index")
                            .clearAndSetSemantics {
                                contentDescription = spoken
                                if (current) stateDescription = currentSpoken
                                if (onSelect != null) {
                                    role = Role.Button
                                    onClick {
                                        onSelect(index)
                                        true
                                    }
                                }
                            }.clip(shape)
                            .background(tokens.surfaceStage)
                            .border(if (current) 1.5.dp else 1.dp, if (current) tokens.inkPrimary else tokens.hairline, shape)
                            .then(touch)
                            .defaultMinSize(minWidth = Spacing.touchMin, minHeight = Spacing.touchMin)
                            .padding(Spacing.s2),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (pictures) {
                        MovePictureView(
                            remember(move) { MovePicture.of(move) },
                            Modifier.size(width = 30.dp, height = 24.dp).testTag("playback:chipPicture:$index"),
                        )
                    }
                    Text(notation, style = RubikTheme.textStyles.notation, color = tokens.inkPrimary)
                }
            }
        }
    }
}
