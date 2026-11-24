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
 * Which stickers likely explain wrong color counts (#174, proximity rule #323). Candidates are the non-center stickers
 * of every color seen more than 9 times that still carry a measured Lab. A candidate qualifies for a color seen fewer
 * than 9 times (with a calibration center) only when its margin, the CIE76 ΔE from its Lab to that color's center minus
 * the ΔE to the center of the color it was read as, is at most [SUSPECT_MARGIN_DELTA_E]; its [Suspect.likely] color is
 * the nearest color it qualifies for. Smallest margin first, ties by lower sticker index; at most [MAX_SUSPECTS], never
 * padded with stickers that do not qualify. A candidate whose read color has no calibration center cannot qualify.
 * Empty when the counts are right, any sticker is blank, no color is short, or no candidate qualifies.
 */
object SuspectRanking {
    const val MAX_SUSPECTS = 3

    /**
     * The largest margin (CIE76 ΔE to the under-counted color's center minus ΔE to the read color's center) at which a
     * sticker is still suggested as that color: 20.0, twice [StickerClassifier.CLOSE_CALL_DELTA_E]. A relative margin,
     * so a sticker measured near the boundary between two colors qualifies while an exact read of a clearly different
     * color never does (PO 2026-11-24, #323).
     */
    const val SUSPECT_MARGIN_DELTA_E = 2 * StickerClassifier.CLOSE_CALL_DELTA_E
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
                val read = evidence.centers[stickers[index]] ?: return@mapNotNull null
                val own = StickerClassifier.deltaE(lab, read)
                val (likely, distance) =
                    under
                        .map { it to StickerClassifier.deltaE(lab, evidence.centers.getValue(it)) }
                        .filter { it.second - own <= SUSPECT_MARGIN_DELTA_E }
                        .minByOrNull { it.second } ?: return@mapNotNull null
                Triple(index, likely, distance - own)
            }.sortedWith(compareBy({ it.third }, { it.first }))
            .take(MAX_SUSPECTS)
            .map { (index, likely, _) -> Suspect(index, likely) }
            .toList()
    }
}
