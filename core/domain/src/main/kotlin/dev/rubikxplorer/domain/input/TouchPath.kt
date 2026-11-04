package dev.rubikxplorer.domain.input

/** Viewport pixels, with y increasing downwards. */
data class TouchPath(
    val points: List<ScreenPoint>,
)
