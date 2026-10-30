package dev.rubikxplorer.application.session

/** What [reduce] returns: the next [state], the [effects] to run and the [presentation] to draw, in order. */
data class Transition(
    val state: SessionState,
    val effects: List<Effect> = emptyList(),
    val presentation: List<Presentation> = emptyList(),
)
