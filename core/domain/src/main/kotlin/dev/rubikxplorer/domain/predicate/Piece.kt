package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.Color

/** A cubie named by its sticker [colors] (2 for an edge, 3 for a corner), so it is orientation-invariant. */
data class Piece(
    val colors: Set<Color>,
)
