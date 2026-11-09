package dev.cubentia.application.freecube

import dev.cubentia.application.session.Presentation

data class FreeCubeTransition(
    val state: FreeCubeState,
    val presentation: Presentation? = null,
) {
    val presentations: List<Presentation> get() = listOfNotNull(presentation)
}
