package dev.cubentia.domain.input

import kotlin.math.hypot

/**
 * Pure double-tap classification: two pointer-downs are a double-tap only when separated by at most
 * [timeoutMillis] in time and at most [slopPx] in screen distance.
 */
fun isDoubleTap(
    firstDownMillis: Long,
    firstDown: ScreenPoint,
    secondDownMillis: Long,
    secondDown: ScreenPoint,
    timeoutMillis: Long,
    slopPx: Double,
): Boolean {
    val dt = secondDownMillis - firstDownMillis
    if (dt < 0 || dt > timeoutMillis) return false
    return hypot(secondDown.x - firstDown.x, secondDown.y - firstDown.y) <= slopPx
}
