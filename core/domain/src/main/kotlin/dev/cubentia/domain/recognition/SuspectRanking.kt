package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Facelets

/** A scanned sticker that may be misread: its net [index] and the under-count color it most likely is. */
data class Suspect(
    val index: Int,
    val likely: Color,
)

/**
 * What a scan measured, for review only (#174): each sticker's CIELAB in net order (null when the sticker was not
 * scanned or the Learner painted it since) and the calibration [centers] the scan classified against. Never shown to the
 * Learner.
 */
data class ScanEvidence(
    val labs: List<Lab?>,
    val centers: Map<Color, Lab>,
)

/**
 * Which stickers likely explain wrong color counts (#174). Candidates are the non-center stickers of every color seen
 * more than 9 times that still carry a measured Lab; each scores the CIE76 ΔE from its Lab to the nearest calibration
 * center of a color seen fewer than 9 times, which is its [Suspect.likely] color. Lowest score first, ties by lower
 * sticker index; at most [MAX_SUSPECTS]. Empty when the counts are right, any sticker is blank, or no color is short.
 */
object SuspectRanking {
    const val MAX_SUSPECTS = 3
    private const val PER_COLOR = 9

    fun rank(
        stickers: List<Color?>,
        evidence: ScanEvidence,
    ): List<Suspect> {
        if (stickers.size != Facelets.SIZE || stickers.any { it == null }) return emptyList()
        val counts = Color.entries.associateWith { color -> stickers.count { it == color } }
        val over = Color.entries.filter { counts.getValue(it) > PER_COLOR }.toSet()
        val under = Color.entries.filter { counts.getValue(it) < PER_COLOR && it in evidence.centers }
        if (over.isEmpty() || under.isEmpty()) return emptyList()
        return stickers.indices
            .asSequence()
            .filter { it % 9 != 4 && stickers[it] in over }
            .mapNotNull { index ->
                val lab = evidence.labs.getOrNull(index) ?: return@mapNotNull null
                val (likely, score) =
                    under
                        .map { it to StickerClassifier.deltaE(lab, evidence.centers.getValue(it)) }
                        .minBy { it.second }
                Triple(index, likely, score)
            }.sortedWith(compareBy({ it.third }, { it.first }))
            .take(MAX_SUSPECTS)
            .map { (index, likely, _) -> Suspect(index, likely) }
            .toList()
    }
}
