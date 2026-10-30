package dev.rubikxplorer.domain.tutor

/**
 * How the tutor teaches a Lesson (FR-5). Every mode evaluates the same Completion Predicate; the mode
 * only decides what [Tutor.decide] reveals. Story 2.6 adds Test me and Let me try.
 */
enum class TeachingMode {
    /** The Demonstration plays; every step's Moves are shown. */
    SHOW_ME,

    /** The Learner turns the cube; each step's Goal comes first and its Moves only on request. */
    GUIDE_ME,
    ;

    companion object {
        /** The mode a Learner starts in before choosing one. */
        val DEFAULT: TeachingMode = GUIDE_ME
    }
}
