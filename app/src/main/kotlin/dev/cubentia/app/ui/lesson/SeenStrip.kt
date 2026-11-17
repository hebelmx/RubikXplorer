package dev.cubentia.app.ui.lesson

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cubentia.app.R
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.preferences.LocalRenderStyle
import dev.cubentia.app.ui.editor.FACE_NAMES
import dev.cubentia.app.ui.editor.colorName
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.application.session.SessionState
import dev.cubentia.domain.appearance.StickerAppearance
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.recognition.PhysicalObservation
import dev.cubentia.domain.tutor.Certainty
import dev.cubentia.domain.text.Text as ContentText

internal val SeenDifferenceKey = SemanticsPropertyKey<Boolean>("SeenDifference")

/** Observed faces only; a Partial is never projected into a whole cube. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeenStrip(
    session: SessionState,
    strings: ContentStrings,
    modifier: Modifier = Modifier,
) {
    val faces =
        when (val observation = session.observed) {
            is PhysicalObservation.Partial -> {
                observation.faces
            }

            is PhysicalObservation.Full -> {
                observation.state.reoriented(Orientation.HOME).toFacelets().colors.let { colors ->
                    Face.entries.associateWith {
                        colors.subList(it.ordinal * 9, it.ordinal * 9 + 9)
                    }
                }
            }

            null -> {
                return
            }
        }
    val expected =
        session.expected
            .reoriented(Orientation.HOME)
            .toFacelets()
            .colors
    val style = LocalRenderStyle.current
    val tokens = RubikTheme.tokens
    val differs = strings.resolve(ContentText("msg.quick-check.seen-differs"))
    val outline = if (session.quickCheck?.certainty == Certainty.VERIFIED) tokens.statusVerified else tokens.statusLikely
    Column(modifier.testTag("stage:seen")) {
        Text(strings.resolve(ContentText("msg.quick-check.seen")))
        // Wraps so all six faces of a Full observation stay visible on a narrow stage.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Face.entries.filter { it in faces }.forEach { face ->
                val colors = faces.getValue(face)
                Column(Modifier.testTag("seen:face:${face.name}")) {
                    repeat(3) { row ->
                        Row {
                            repeat(3) { col ->
                                val index = row * 3 + col
                                val different = colors[index] != expected[face.ordinal * 9 + index]
                                val appearance = StickerAppearance.of(colors[index])
                                val description =
                                    stringResource(
                                        R.string.editor_sticker,
                                        stringResource(colorName(colors[index])),
                                        stringResource(FACE_NAMES[face.ordinal]),
                                        index + 1,
                                    )
                                Box(
                                    Modifier
                                        .size(
                                            18.dp,
                                        ).background(
                                            androidx.compose.ui.graphics
                                                .Color(appearance.fill),
                                        ).then(if (different) Modifier.border(2.dp, outline) else Modifier)
                                        .semantics {
                                            set(SeenDifferenceKey, different)
                                            contentDescription = description
                                            if (different) stateDescription = differs
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (style.colorBlindGlyphs) {
                                        Text(
                                            appearance.glyph.toString(),
                                            color =
                                                androidx.compose.ui.graphics
                                                    .Color(appearance.glyphInk),
                                            fontSize = 10.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
