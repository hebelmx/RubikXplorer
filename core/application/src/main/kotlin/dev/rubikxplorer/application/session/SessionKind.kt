package dev.rubikxplorer.application.session

/** What a `LearningSession` is for (AD-3). Lessons run as [LESSON]; the others arrive with later epics. */
enum class SessionKind {
    LESSON,
    PRACTICE,
    TWO_LOOK,
    SOLVE_PLAYBACK,
}
