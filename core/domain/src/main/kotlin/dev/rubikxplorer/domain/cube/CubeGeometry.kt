package dev.rubikxplorer.domain.cube

/**
 * Physical dimensions of the rendered cube in the viewer frame (unit cubie spacing, cubie centres
 * at integer coordinates). The single source of truth shared by the 3D renderer and gesture picking.
 */
object CubeGeometry {
    /** Half the edge of a cubie body; the gap between neighbouring bodies is 1 − 2 × this. */
    const val BODY_HALF = 0.47f

    /** Half the edge of a sticker quad. */
    const val STICKER_HALF = 0.40f

    /** Distance of a sticker quad from its cubie centre along the sticker normal. */
    const val STICKER_LIFT = 0.472f
}
