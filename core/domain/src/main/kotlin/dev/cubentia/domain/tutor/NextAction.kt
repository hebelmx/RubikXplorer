package dev.cubentia.domain.tutor

/** What the Learner does next, as the tutor decides it (AD-5). */
enum class NextAction {
    /** Watch the Demonstration. */
    WATCH,

    /** Turn the cube. */
    TURN,

    /** Move on to the next step. */
    NEXT_STEP,

    /** The Lesson is complete. */
    FINISH,
}
