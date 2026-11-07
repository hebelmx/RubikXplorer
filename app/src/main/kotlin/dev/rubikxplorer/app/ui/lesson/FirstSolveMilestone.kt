package dev.rubikxplorer.app.ui.lesson

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.domain.appearance.StickerAppearance
import kotlin.math.cos
import kotlin.math.sin
import dev.rubikxplorer.domain.cube.Color as CubeColor

/**
 * The six sticker colors in fixed order for the milestone burst.
 */
private val STICKER_COLORS: List<Color> =
    listOf(
        CubeColor.WHITE,
        CubeColor.GREEN,
        CubeColor.RED,
        CubeColor.BLUE,
        CubeColor.ORANGE,
        CubeColor.YELLOW,
    ).map { Color(StickerAppearance.of(it).fill) }

private const val BURST_DURATION_MS = 1000

/**
 * First-solve milestone moment (UX-DR20, UX-DR24): shown once in virtual mode when the Learner
 * meets the Objective of the final Lesson of Stage 6 for the first time.
 *
 * Shows a ≤ 1.2 s burst of the six sticker colors, followed by the headline in display typography,
 * with no looping animation or sound. Under [reduceMotion], the burst is rendered as a static frame.
 */
@Composable
fun FirstSolveMilestone(
    headline: String,
    onPracticeNewScramble: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = rememberReduceMotion(),
) {
    val tokens = RubikTheme.tokens
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }

    LaunchedEffect(reduceMotion) {
        if (!reduceMotion) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = BURST_DURATION_MS, easing = FastOutSlowInEasing),
            )
        } else {
            progress.snapTo(1f)
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(tokens.surfaceBase.copy(alpha = 0.95f))
                .testTag("milestone:overlay"),
        contentAlignment = Alignment.Center,
    ) {
        // Six-color celebratory burst/ring
        MilestoneBurstGraphic(
            progress = progress.value,
            modifier = Modifier.size(240.dp).testTag("milestone:burst"),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(modifier = Modifier.height(140.dp))

            Text(
                text = headline,
                style = MaterialTheme.typography.displaySmall,
                color = tokens.inkPrimary,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { heading() }
                        .testTag("milestone:headline"),
            )

            Spacer(modifier = Modifier.height(Spacing.s6))

            Button(
                onClick = onPracticeNewScramble,
                colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = tokens.onAccent),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(Spacing.touchMin)
                        .testTag("milestone:practice"),
            ) {
                Text(
                    text = stringResource(R.string.milestone_practice),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.s3))

            OutlinedButton(
                onClick = onContinue,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.inkPrimary),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(Spacing.touchMin)
                        .testTag("milestone:continue"),
            ) {
                Text(
                    text = stringResource(R.string.milestone_continue),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun MilestoneBurstGraphic(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension / 2f * 0.85f
        val radius = maxRadius * (0.3f + 0.7f * progress)
        val dotRadius = 14.dp.toPx() * (0.6f + 0.4f * progress)
        val angleStep = (2.0 * Math.PI / STICKER_COLORS.size).toFloat()

        STICKER_COLORS.forEachIndexed { index, color ->
            val angle = index * angleStep - (Math.PI / 2.0).toFloat()
            val offset =
                Offset(
                    x = center.x + radius * cos(angle),
                    y = center.y + radius * sin(angle),
                )
            drawCircle(
                color = color,
                radius = dotRadius,
                center = offset,
            )
        }
    }
}
