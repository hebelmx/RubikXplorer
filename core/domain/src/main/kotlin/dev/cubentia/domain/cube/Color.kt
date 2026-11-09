package dev.cubentia.domain.cube

import kotlinx.serialization.Serializable

/** Sticker colors. In the home frame WHITE is on U, GREEN on F and RED on R. */
@Serializable
enum class Color {
    WHITE,
    YELLOW,
    GREEN,
    BLUE,
    RED,
    ORANGE,
    ;

    companion object {
        /** Center color of each home face. */
        fun home(face: Face): Color =
            when (face) {
                Face.U -> WHITE
                Face.R -> RED
                Face.F -> GREEN
                Face.D -> YELLOW
                Face.L -> ORANGE
                Face.B -> BLUE
            }
    }
}
