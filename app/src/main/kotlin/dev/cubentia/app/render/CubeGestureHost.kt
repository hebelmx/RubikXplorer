package dev.cubentia.app.render

import android.view.ViewConfiguration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.cubentia.app.preferences.LocalHaptics
import dev.cubentia.domain.input.DragPreview
import dev.cubentia.domain.input.GestureInput
import dev.cubentia.domain.input.GestureInterpreter
import dev.cubentia.domain.input.MoveIntent
import dev.cubentia.domain.input.PairKind
import dev.cubentia.domain.input.ScreenPoint
import dev.cubentia.domain.input.TouchPath
import dev.cubentia.domain.input.ViewPose
import dev.cubentia.domain.input.isDoubleTap

@Composable
internal fun cubeGestureInput(
    renderer: Cube3DRenderer,
    onMoveIntent: ((MoveIntent) -> Unit)?,
    onDragStart: (() -> Unit)?,
    freeViewRotation: Boolean,
    reduceMotion: Boolean,
): Modifier {
    val commit by rememberUpdatedState(onMoveIntent)
    val dragStart by rememberUpdatedState(onDragStart)
    val reduced by rememberUpdatedState(reduceMotion)
    val haptics = LocalHapticFeedback.current
    val tick by rememberUpdatedState<() -> Unit>(
        if (LocalHaptics.current) ({ haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap) }) else ({}),
    )
    val context = LocalContext.current
    val doubleTapSlopPx = remember(context) { ViewConfiguration.get(context).scaledDoubleTapSlop.toDouble() }
    return Modifier.pointerInput(renderer, freeViewRotation, onMoveIntent != null, doubleTapSlopPx) {
        var previousTapMillis: Long? = null
        var previousTapPoint: ScreenPoint? = null
        awaitEachGesture {
            val down = awaitFirstDown()
            renderer.cancelViewReset()
            renderer.clearAnnouncement()
            val first = down.position
            val firstPoint = first.screenPoint()
            val pathA = mutableListOf(firstPoint)
            val pathB = mutableListOf<ScreenPoint>()
            var secondId = currentEvent.changes.firstOrNull { it.id != down.id && it.pressed }?.id
            secondId?.let { id ->
                val second = currentEvent.changes.first { it.id == id }
                pathB.add(second.position.screenPoint())
                second.consume()
            }
            var baseOrbit = renderer.orbit
            var pose = ViewPose(size.width, size.height, baseOrbit.eye())
            val side = minOf(size.width, size.height).toDouble()
            var onCube = !freeViewRotation && startsOnCube(pathA.first(), pathB.firstOrNull(), pose)
            // Both fingers off the cube around it: a View Rotation that can still become a z twist (only when Moves are wired).
            var midpointOnly =
                commit != null && !freeViewRotation && !onCube && midpointOnCube(pathA.first(), pathB.firstOrNull(), pose)
            var twisted = false
            var began = false
            var moved = false
            var frozen = false
            var completed = false
            var preview: DragPreview? = null
            down.consume()
            try {
                do {
                    val event = awaitPointerEvent()
                    val primary = event.changes.firstOrNull { it.id == down.id }
                    if (primary == null && !frozen) break
                    if (secondId == null && preview == null && primary?.pressed == true) {
                        val second = event.changes.firstOrNull { it.id != down.id && it.pressed }
                        if (second != null) {
                            secondId = second.id
                            pathA.clear()
                            pathA.add(primary.position.screenPoint())
                            pathB.add(second.position.screenPoint())
                            renderer.cancelViewReset()
                            baseOrbit = renderer.orbit
                            pose = ViewPose(size.width, size.height, baseOrbit.eye())
                            onCube = !freeViewRotation && startsOnCube(pathA.first(), pathB.first(), pose)
                            midpointOnly =
                                commit != null && !freeViewRotation && !onCube && midpointOnCube(pathA.first(), pathB.first(), pose)
                        }
                    }
                    val second = event.changes.firstOrNull { it.id == secondId }
                    if (primary?.isConsumed == true || second?.isConsumed == true) break
                    if (primary != null && (primary.position - first).getDistance() >= viewConfiguration.touchSlop) moved = true
                    if (second != null && pathB.isNotEmpty()) {
                        val secondStart = Offset(pathB.first().x.toFloat(), pathB.first().y.toFloat())
                        if ((second.position - secondStart).getDistance() >= viewConfiguration.touchSlop) moved = true
                    }
                    if (!frozen && primary != null) {
                        val a = primary.position.screenPoint()
                        val b = second?.position?.screenPoint()
                        val changed = a != pathA.last() || (b != null && b != pathB.last())
                        if (changed) {
                            // Paired samples stay aligned even when only one pointer moves.
                            pathA.add(a)
                            if (b != null) pathB.add(b)
                            // Jitter below touch slop (one finger or a pair) is not a drag: it must not finish the queue or stop a Lesson job.
                            if (onCube && commit != null && moved) {
                                if (!began) {
                                    dragStart?.invoke()
                                    renderer.beginDrag()
                                    began = true
                                }
                                val paths = if (b == null) listOf(TouchPath(pathA)) else listOf(TouchPath(pathA), TouchPath(pathB))
                                preview = GestureInterpreter.preview(GestureInput(paths), pose)
                                renderer.showPreview(preview, primary.position, reduced)
                            } else if (!onCube) {
                                if (midpointOnly && b != null &&
                                    GestureInterpreter.classifyPair(TouchPath(pathA), TouchPath(pathB), pose) == PairKind.TWIST
                                ) {
                                    twisted = true
                                }
                                val start = midpoint(pathA.first(), pathB.firstOrNull())
                                val end = midpoint(a, b)
                                if (twisted) {
                                    renderer.dragViewFrom(baseOrbit, 0.0, 0.0, side)
                                } else {
                                    renderer.dragViewFrom(baseOrbit, end.x - start.x, end.y - start.y, side)
                                }
                            }
                        }
                        if (secondId != null && (!primary.pressed || second?.pressed != true)) frozen = true
                    }
                    primary?.consume()
                    second?.consume()
                    if (primary?.pressed != true && second?.pressed != true) {
                        completed = true
                        break
                    }
                } while (true)

                val callback = commit
                if (completed && callback != null && began) {
                    val active = preview as? DragPreview.Active
                    val intent =
                        when {
                            active != null -> {
                                MoveIntent(active.move)
                            }

                            preview == DragPreview.CancelZone -> {
                                null
                            }

                            pathB.isNotEmpty() && GestureInterpreter.classifyPair(
                                TouchPath(pathA),
                                TouchPath(pathB),
                                pose,
                            ) == PairKind.TWIST -> {
                                GestureInterpreter.interpretTwist(pathA.first(), pathB.first(), pathA.last(), pathB.last(), pose)
                            }

                            else -> {
                                null
                            }
                        }
                    if (intent != null) {
                        if (active != null) renderer.holdForCommit()
                        callback(intent)
                        tick()
                    } else if (preview == DragPreview.CancelZone) {
                        renderer.announceCancelled()
                    }
                }
                if (completed && callback != null && midpointOnly && twisted && pathB.isNotEmpty()) {
                    GestureInterpreter.interpretTwist(pathA.first(), pathB.first(), pathA.last(), pathB.last(), pose)?.let {
                        callback(it)
                        tick()
                    }
                }
                if (completed && secondId == null && !moved && preview == null) {
                    val prevMillis = previousTapMillis
                    val prevPoint = previousTapPoint
                    if (prevMillis != null && prevPoint != null &&
                        isDoubleTap(
                            prevMillis,
                            prevPoint,
                            down.uptimeMillis,
                            firstPoint,
                            viewConfiguration.doubleTapTimeoutMillis,
                            doubleTapSlopPx,
                        )
                    ) {
                        renderer.resetView()
                        previousTapMillis = null
                        previousTapPoint = null
                    } else {
                        previousTapMillis = down.uptimeMillis
                        previousTapPoint = firstPoint
                    }
                } else {
                    previousTapMillis = null
                    previousTapPoint = null
                }
            } finally {
                if (!completed) renderer.clearAnnouncement()
                renderer.endDrag()
            }
        }
    }
}

private fun Offset.screenPoint() = ScreenPoint(x.toDouble(), y.toDouble())

private fun midpoint(
    a: ScreenPoint,
    b: ScreenPoint?,
): ScreenPoint = if (b == null) a else ScreenPoint((a.x + b.x) / 2, (a.y + b.y) / 2)

private fun startsOnCube(
    a: ScreenPoint,
    b: ScreenPoint?,
    pose: ViewPose,
): Boolean = GestureInterpreter.startsOnCube(a, pose) || (b != null && GestureInterpreter.startsOnCube(b, pose))

private fun midpointOnCube(
    a: ScreenPoint,
    b: ScreenPoint?,
    pose: ViewPose,
): Boolean = b != null && GestureInterpreter.startsOnCube(midpoint(a, b), pose)
