package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color

/**
 * One operator-confirmed reference sticker (Story 10.34): its [color] (null = an explicit unknown), where the truth came
 * from ([source], null while the sticker was never set) and whether the operator tapped or colour-picked it ([reviewed]).
 */
data class StickerTruth(
    val color: Color?,
    val source: TruthSource?,
    val reviewed: Boolean,
)

/** What a [StickerTruthCheck] decides for a labelled group: saved as counted, saved with an operator reason, or refused. */
sealed interface StickerTruthDecision {
    data object Passed : StickerTruthDecision

    data class Overridden(
        val reason: String,
    ) : StickerTruthDecision

    data object Refused : StickerTruthDecision
}

/**
 * The format-neutral Story 10.28 rule over the 54 stickers of one labelled cube (Story 10.34): nine of each colour,
 * unknowns counted, and an off-count group saved only with a non-blank operator reason. Both labellers call it: the
 * two-hold corner recorder (its two holds' six faces flattened) and the continuous-session labeller.
 *
 * [counts] holds every [Color] (centres included); [unknownCount] the stickers left as explicit unknowns. The refusal
 * copy is developer text and stays with the debug recorder (`truthRefusal` in `RecorderRules.kt`, AD-15).
 */
data class StickerTruthCheck(
    val counts: Map<Color, Int>,
    val unknownCount: Int = 0,
) {
    val over: Map<Color, Int> get() = counts.filterValues { it > STICKERS_PER_COLOR }
    val under: Map<Color, Int> get() = counts.filterValues { it < STICKERS_PER_COLOR }

    /** True only with exactly nine of each colour and no unknown sticker. */
    val passed: Boolean get() = over.isEmpty() && under.isEmpty() && unknownCount == 0

    /** [StickerTruthDecision.Passed] when [passed]; otherwise overridden with the trimmed non-blank [reason], else refused. */
    fun decide(reason: String?): StickerTruthDecision {
        if (passed) return StickerTruthDecision.Passed
        val trimmed = reason?.let(::overrideReason) ?: return StickerTruthDecision.Refused
        return StickerTruthDecision.Overridden(trimmed)
    }

    companion object {
        /** How many stickers of each colour a real cube has. */
        const val STICKERS_PER_COLOR = 9

        /** Stickers on a cube. */
        const val STICKERS = 54

        /** The check over 54 truth stickers (URFDLB row-major for a continuous session, hold order for a corner group). */
        fun of(stickers: List<StickerTruth>): StickerTruthCheck {
            require(stickers.size == STICKERS) { "StickerTruthCheck needs $STICKERS stickers, got ${stickers.size}" }
            return ofColors(stickers.map { it.color })
        }

        /** The check over any list of colours; a null colour counts as unknown. */
        fun ofColors(colors: List<Color?>): StickerTruthCheck =
            StickerTruthCheck(
                counts = Color.entries.associateWith { color -> colors.count { it == color } },
                unknownCount = colors.count { it == null },
            )

        /** The override reason: the trimmed [text], or null when it is empty or spaces only (no override then). */
        fun overrideReason(text: String): String? = text.trim().takeIf { it.isNotEmpty() }
    }
}
