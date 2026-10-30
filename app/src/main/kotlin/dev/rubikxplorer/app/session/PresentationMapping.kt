package dev.rubikxplorer.app.session

import dev.rubikxplorer.app.render.PresentationItem
import dev.rubikxplorer.application.session.Presentation

/** The renderer's [PresentationItem] for this reducer [Presentation]. */
fun Presentation.toItem(): PresentationItem =
    when (this) {
        is Presentation.Animate -> PresentationItem.Animate(move, revision, target.toFacelets())
        is Presentation.Snap -> PresentationItem.Snap(revision, target.toFacelets())
    }

/** [toItem] for each, in order. */
fun List<Presentation>.toItems(): List<PresentationItem> = map { it.toItem() }
