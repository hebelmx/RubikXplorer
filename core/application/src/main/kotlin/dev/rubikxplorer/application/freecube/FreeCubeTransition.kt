package dev.rubikxplorer.application.freecube

import dev.rubikxplorer.application.session.Presentation

data class FreeCubeTransition(
    val state: FreeCubeState,
    val presentation: Presentation? = null,
) {
    val presentations: List<Presentation> get() = listOfNotNull(presentation)
}
