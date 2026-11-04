package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.text.Text

/**
 * How a committed Move in *Test me* related to the step the Learner is on (FR-11, AD-5): a closed
 * set, produced only by [Assess.classify]. Calm copy in every case; a Harmful Move is never shown in
 * red, and its line is its Diagnosis, so [HARMFUL] has no [text] of its own.
 */
enum class MoveFeedback(
    val text: Text?,
) {
    /** The next authored Move from an authored path state (either quarter of an authored half turn counts). */
    ON_PATH(Text("msg.test-me.on-path")),

    /** Off the path, the step's effective-done progress rose and nothing preserved was disturbed. */
    PROGRESS(Text("msg.test-me.progress")),

    /** Off the path, nothing preserved was disturbed and the step's progress did not rise. */
    NEUTRAL(Text("msg.test-me.neutral")),

    /** The Move disturbed a piece the step preserves; the Diagnosis says which and how to get back. */
    HARMFUL(null),
}
