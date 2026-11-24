package dev.cubentia.app.ui.editor

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.text.Text as ContentText

/**
 * Asks before leaving a Manual Cube Editor that holds the Learner's input (GH #354), following Guided Scan's discard
 * dialog (GH #325): *Discard* calls [onDiscard]; *Keep entering*, Back or a tap outside calls [onKeep], which keeps the
 * editor and its stickers. Tagged [tag], `[tag]:confirm` and `[tag]:cancel`.
 */
@Composable
fun EditorDiscardDialog(
    strings: ContentStrings,
    tag: String,
    onDiscard: () -> Unit,
    onKeep: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(strings.resolve(ContentText("msg.editor.discard-title"))) },
        confirmButton = {
            TextButton(onClick = onDiscard, modifier = Modifier.testTag("$tag:confirm")) {
                Text(strings.resolve(ContentText("btn.editor.discard")))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep, modifier = Modifier.testTag("$tag:cancel")) {
                Text(strings.resolve(ContentText("btn.editor.keep-entering")))
            }
        },
        modifier = Modifier.testTag(tag),
    )
}

/** Whether [edited] differs from [opened] on any sticker the Learner can change (centers are fixed to home colors). */
fun editedFrom(
    opened: Facelets,
    edited: Facelets,
): Boolean = (0 until Facelets.SIZE).any { ManualCubeEditorState.isEditable(it) && opened.colors[it] != edited.colors[it] }
