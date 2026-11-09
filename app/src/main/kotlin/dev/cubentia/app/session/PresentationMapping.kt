package dev.cubentia.app.session

import dev.cubentia.app.render.PresentationItem
import dev.cubentia.application.session.Presentation

/** The renderer's [PresentationItem] for this reducer [Presentation]. */
fun Presentation.toItem(): PresentationItem =
    when (this) {
        is Presentation.Animate -> PresentationItem.Animate(move, revision, target.toFacelets())

        is Presentation.Snap -> PresentationItem.Snap(revision, target.toFacelets())

        // The Learner turns their own cube; the on-screen cube just snaps to the target.
        is Presentation.Instruct -> PresentationItem.Snap(revision, target.toFacelets())
    }

/** [toItem] for each, in order. */
fun List<Presentation>.toItems(): List<PresentationItem> = map { it.toItem() }
