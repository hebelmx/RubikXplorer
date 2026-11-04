package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.AdaptiveThresholds
import dev.rubikxplorer.domain.text.Text

/**
 * How much help adaptive assistance (FR-13) gives on the shown step, acting only on its Hint Ladder:
 * the shown rung starts at [floor] and climbs no higher than [top]. Declared from least to most help,
 * so levels compare by help given.
 */
enum class AssistanceLevel(
    val floor: HintRung,
    val top: HintRung,
) {
    /** Repeated success: fewer Hints, the ladder stops at the target ("Hint (n/2)"). */
    REDUCED(HintRung.NONE, HintRung.TARGET),

    /** The usual Hint Ladder: no change. */
    USUAL(HintRung.NONE, HintRung.DEMONSTRATION),

    /** A first mistake: the clue shows untapped and the target piece is outlined. */
    EXPLAIN(HintRung.CLUE, HintRung.DEMONSTRATION),

    /** A repeated mistake: the target is outlined and the step's Moves are shown. */
    DEMONSTRATE(HintRung.TARGET, HintRung.DEMONSTRATION),

    /** Continued difficulty: like [DEMONSTRATE], and the Move chips show one Move at a time. */
    GUIDE(HintRung.TARGET, HintRung.DEMONSTRATION),
}

/**
 * Rule-based adaptive assistance (FR-13, AD-18): pure, and tuned only by the [AdaptiveThresholds] in
 * `tutor-config.json`, never by code constants or data from use.
 */
object AdaptivePolicy {
    /**
     * The level for a step with [mistakes] Harmful Moves after [cleanSteps] steps in a row completed
     * cleanly, first match: guide, demonstrate, explain (each at its mistake threshold), reduced (at
     * the clean-step threshold), else usual.
     */
    fun level(
        thresholds: AdaptiveThresholds,
        mistakes: Int,
        cleanSteps: Int,
    ): AssistanceLevel =
        when {
            mistakes >= thresholds.guideAfterMistakes -> AssistanceLevel.GUIDE
            mistakes >= thresholds.demonstrateAfterMistakes -> AssistanceLevel.DEMONSTRATE
            mistakes >= thresholds.explainAfterMistakes -> AssistanceLevel.EXPLAIN
            cleanSteps >= thresholds.reduceAfterCleanSteps -> AssistanceLevel.REDUCED
            else -> AssistanceLevel.USUAL
        }

    /** The Assistance line saying why help changed at [level] after [mistakes]; null at [AssistanceLevel.USUAL]. */
    fun line(
        level: AssistanceLevel,
        mistakes: Int,
    ): Text? {
        val key =
            when (level) {
                AssistanceLevel.USUAL -> return null
                AssistanceLevel.REDUCED -> "msg.assistance.reduced"
                AssistanceLevel.EXPLAIN -> "msg.assistance.explain"
                AssistanceLevel.DEMONSTRATE -> "msg.assistance.demonstrate"
                AssistanceLevel.GUIDE -> "msg.assistance.guide"
            }
        return Text(key, mapOf("count" to mistakes))
    }
}
