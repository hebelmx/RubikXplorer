package dev.rubikxplorer.domain.playback

/** Playback speed: one Move every [PlaybackController.STEP_MILLIS] / [factor] milliseconds. */
enum class PlaybackSpeed(
    val factor: Double,
) {
    HALF(0.5),
    NORMAL(1.0),
    DOUBLE(2.0),
    ;

    /** Milliseconds between two Moves at this speed. */
    val stepMillis: Long
        get() = (PlaybackController.STEP_MILLIS / factor).toLong()
}
