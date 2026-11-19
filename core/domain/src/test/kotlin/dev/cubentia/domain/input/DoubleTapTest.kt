package dev.cubentia.domain.input

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoubleTapTest {
    private val timeout = 300L
    private val slop = 100.0

    @Test
    fun `near and timely second tap is a double tap`() {
        assertTrue(
            isDoubleTap(
                firstDownMillis = 1_000L,
                firstDown = ScreenPoint(120.0, 140.0),
                secondDownMillis = 1_180L,
                secondDown = ScreenPoint(160.0, 170.0),
                timeoutMillis = timeout,
                slopPx = slop,
            ),
        )
        // Exact boundary on time and distance.
        assertTrue(
            isDoubleTap(
                firstDownMillis = 1_000L,
                firstDown = ScreenPoint(0.0, 0.0),
                secondDownMillis = 1_300L,
                secondDown = ScreenPoint(60.0, 80.0),
                timeoutMillis = timeout,
                slopPx = slop,
            ),
        )
    }

    @Test
    fun `far apart taps within timeout are not a double tap`() {
        assertFalse(
            isDoubleTap(
                firstDownMillis = 1_000L,
                firstDown = ScreenPoint(50.0, 50.0),
                secondDownMillis = 1_100L,
                secondDown = ScreenPoint(200.0, 50.0),
                timeoutMillis = timeout,
                slopPx = slop,
            ),
        )
    }

    @Test
    fun `late or reversed second tap at same spot is not a double tap`() {
        assertFalse(
            isDoubleTap(
                firstDownMillis = 1_000L,
                firstDown = ScreenPoint(50.0, 50.0),
                secondDownMillis = 1_301L,
                secondDown = ScreenPoint(50.0, 50.0),
                timeoutMillis = timeout,
                slopPx = slop,
            ),
        )
        assertFalse(
            isDoubleTap(
                firstDownMillis = 1_000L,
                firstDown = ScreenPoint(50.0, 50.0),
                secondDownMillis = 999L,
                secondDown = ScreenPoint(50.0, 50.0),
                timeoutMillis = timeout,
                slopPx = slop,
            ),
        )
    }
}
