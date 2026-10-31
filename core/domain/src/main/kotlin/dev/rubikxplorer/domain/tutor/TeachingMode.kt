package dev.rubikxplorer.domain.tutor

/**
 * How the tutor teaches a Lesson (FR-5). Every mode evaluates the same Completion Predicate; the mode
 * only decides what [Tutor.decide] reveals and whether it shows [MoveFeedback].
 */
enum class TeachingMode {
    /** The Demonstration plays; every step's Moves are shown. */
    SHOW_ME,

    /** The Learner turns the cube; each step's Goal comes first and its Moves only on request. */
    GUIDE_ME,

    /** The Learner picks each Move and is told whether it helped; the Moves show only after a Hint. */
    TEST_ME,

    /** The Learner works the step with only Goal, Keep and Watch; the Moves show only after a Hint. */
    LET_ME_TRY,
    ;

    companion object {
        /** The mode a Learner starts in before choosing one. */
        val DEFAULT: TeachingMode = GUIDE_ME
    }
}
