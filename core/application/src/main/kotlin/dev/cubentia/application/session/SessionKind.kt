package dev.cubentia.application.session

/** What a `LearningSession` is for (AD-3). Course Lessons run as [LESSON], 2-look Lessons as [TWO_LOOK], Solutions play as [SOLVE_PLAYBACK]; [isTaught] covers the first two. */
enum class SessionKind {
    LESSON,
    PRACTICE,
    TWO_LOOK,
    SOLVE_PLAYBACK,
    ;

    val isTaught: Boolean get() = this == LESSON || this == TWO_LOOK

    val takesMoves: Boolean get() = this != SOLVE_PLAYBACK

    val resumesOnColdStart: Boolean get() = this != SOLVE_PLAYBACK
}
