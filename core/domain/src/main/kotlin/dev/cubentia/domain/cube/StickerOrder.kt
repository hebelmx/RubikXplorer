package dev.cubentia.domain.cube

/**
 * The sticker review net's advance order: sticker index order 0..53 ([Facelets] URFDLB, row-major per face), centers
 * skipped. It drives only the next-sticker step: the Manual Cube Editor's move to the next sticker after a color is
 * set, and Scan review's move to the next blank (Scan review's suspect jump and preselection do not use it).
 */
object StickerOrder {
    private const val PER_FACE = 9
    private const val CENTER_CELL = 4

    /**
     * The first non-center sticker after [after] that satisfies [where]. Without [wrap] the scan stops at 53 and
     * returns null; with [wrap] it scans `after+1 .. after+54` mod 54, so it may return [after] itself.
     */
    fun next(
        after: Int,
        wrap: Boolean = false,
        where: (Int) -> Boolean = { true },
    ): Int? {
        val candidates =
            if (wrap) {
                (1..Facelets.SIZE).asSequence().map { (after + it) % Facelets.SIZE }
            } else {
                (after + 1 until Facelets.SIZE).asSequence()
            }
        return candidates.firstOrNull { it % PER_FACE != CENTER_CELL && where(it) }
    }
}
