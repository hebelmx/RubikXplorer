package dev.rubikxplorer.domain.content

import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.Facelets

/**
 * The content form of [Facelets]: 54 color letters `W Y G B R O` in URFDLB order, each face
 * row-major. The letters fully determine a state, since [dev.rubikxplorer.domain.cube.CubeValidator]
 * derives orientation from the centers.
 */
object FaceletString {
    private val letters: Map<Color, Char> =
        mapOf(
            Color.WHITE to 'W',
            Color.YELLOW to 'Y',
            Color.GREEN to 'G',
            Color.BLUE to 'B',
            Color.RED to 'R',
            Color.ORANGE to 'O',
        )
    private val colors: Map<Char, Color> = letters.entries.associate { (color, letter) -> letter to color }

    /** The [Facelets] [text] spells, or why it spells none. Never throws. */
    fun decode(text: String): FaceletStringResult {
        if (text.length != Facelets.SIZE) return FaceletStringResult.WrongLength(text.length)
        val decoded =
            text.mapIndexed { index, letter ->
                colors[letter] ?: return FaceletStringResult.BadLetter(index, letter)
            }
        return FaceletStringResult.Decoded(Facelets(decoded))
    }

    /** The 54-letter string for [facelets]. */
    fun encode(facelets: Facelets): String = facelets.colors.map { letters.getValue(it) }.joinToString("")
}

/** The outcome of [FaceletString.decode]. */
sealed interface FaceletStringResult {
    data class Decoded(
        val facelets: Facelets,
    ) : FaceletStringResult

    /** The text is not 54 letters long. */
    data class WrongLength(
        val length: Int,
    ) : FaceletStringResult

    /** The letter at [index] is not one of `W Y G B R O`. */
    data class BadLetter(
        val index: Int,
        val letter: Char,
    ) : FaceletStringResult
}
