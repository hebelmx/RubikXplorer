package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.playback.Demonstration
import dev.rubikxplorer.domain.playback.DemonstrationResult
import dev.rubikxplorer.domain.predicate.Piece
import dev.rubikxplorer.domain.predicate.WhitePieceSolved
import dev.rubikxplorer.domain.text.Text

/**
 * A rung of the Hint Ladder (FR-11): how much help the Learner asked for on the shown step. Each
 * Hint climbs exactly one rung; the rung resets with the shown step and on a new Objective (AD-6).
 */
enum class HintRung {
    /** No Hint yet: the Moves stay hidden in *Test me* and *Let me try*. */
    NONE,

    /** A conceptual clue derived from the step's predicates ([HintLadder.clue]). */
    CLUE,

    /** The target piece is outlined and the step's Moves are revealed. */
    TARGET,

    /** The step's Demonstration plays on the cube; the top of the ladder. */
    DEMONSTRATION,
    ;

    /** The rung above this one; [DEMONSTRATION] stays where it is. */
    fun next(): HintRung = entries.getOrElse(ordinal + 1) { this }
}

/**
 * The Hint Ladder's pure rules (FR-11): when a Hint may climb, the clue of a step derived from its
 * predicates (never a content field, AD-6), and the step's target pieces. No rung ever shows more
 * than the current step's Moves.
 */
object HintLadder {
    /**
     * The number of rungs above [HintRung.NONE] (3); the button reads `Hint (n/RUNGS)`. Derived from
     * the closed [HintRung] set, so tutor code declares no numeric constant.
     */
    val RUNGS: Int = HintRung.DEMONSTRATION.ordinal

    /**
     * True when a Hint may climb from [rung]: not in [TeachingMode.SHOW_ME] (everything is shown), not
     * at the top, and not while the tutor says [advance] (the step is done; its Notice speaks).
     */
    fun canClimb(
        mode: TeachingMode,
        rung: HintRung,
        advance: Boolean,
    ): Boolean = mode != TeachingMode.SHOW_ME && rung != HintRung.DEMONSTRATION && !advance

    /**
     * The conceptual clue for step [step] of [lesson]: `msg.hint.clue.piece` naming the piece when the
     * step brings exactly one canonical white-layer piece into place ([target]), else the generic
     * `msg.hint.clue.goal`.
     */
    fun clue(
        lesson: Lesson,
        step: Int,
    ): Text {
        val name = target(lesson, step).singleOrNull()?.let { WhitePieceSolved.nameOf(it) }
        return if (name != null) {
            Text("msg.hint.clue.piece", mapOf("piece" to DiagnosisRules.selectId(name)))
        } else {
            Text("msg.hint.clue.goal")
        }
    }

    /**
     * The line the Learner reads at [rung] on step [step] of [lesson]: the [clue] at [HintRung.CLUE];
     * at [HintRung.TARGET], `msg.hint.target` when the step has exactly one target piece to outline,
     * else `msg.hint.moves`; `msg.hint.demonstration` at the top; nothing at [HintRung.NONE].
     */
    fun line(
        lesson: Lesson,
        step: Int,
        rung: HintRung,
    ): Text? =
        when (rung) {
            HintRung.NONE -> null
            HintRung.CLUE -> clue(lesson, step)
            HintRung.TARGET -> if (target(lesson, step).size == 1) Text("msg.hint.target") else Text("msg.hint.moves")
            HintRung.DEMONSTRATION -> Text("msg.hint.demonstration")
        }

    /**
     * The pieces step [step] of [lesson] newly brings into the Completion Predicate's preserved set
     * (its Demonstration focus target); empty when the Demonstration cannot be built or [step] is out
     * of range.
     */
    fun target(
        lesson: Lesson,
        step: Int,
    ): Set<Piece> {
        val demonstration = (Demonstration.of(lesson) as? DemonstrationResult.Ready)?.demonstration ?: return emptySet()
        return demonstration.steps
            .getOrNull(step)
            ?.focus
            ?.target
            .orEmpty()
    }

    /**
     * The pieces outlined at [HintRung.TARGET] on step [step] of [lesson]: its [target] when it has
     * one; else, for a setup step that brings nothing into place itself, the pieces of the nearest
     * later step's target that this step's Moves carry (its focus `involved`). Empty when neither
     * exists, the Demonstration cannot be built, or [step] is out of range.
     */
    fun highlight(
        lesson: Lesson,
        step: Int,
    ): Set<Piece> {
        val demonstration = (Demonstration.of(lesson) as? DemonstrationResult.Ready)?.demonstration ?: return emptySet()
        val focus = demonstration.steps.getOrNull(step)?.focus ?: return emptySet()
        if (focus.target.isNotEmpty()) return focus.target
        return demonstration.steps
            .drop(step + 1)
            .firstOrNull { it.focus.target.isNotEmpty() }
            ?.focus
            ?.target
            .orEmpty()
            .intersect(focus.involved)
    }
}
