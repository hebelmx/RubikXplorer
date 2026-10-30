package dev.rubikxplorer.app.render

import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Move

/** One step of the presentation queue; [target] is the committed state's facelets after the step. */
sealed interface PresentationItem {
    /** Increases with every submitted item; identifies the committed state the item ends on ([target]). */
    val revision: Long
    val target: Facelets

    /** Animate [move] (Undo submits the inverse of the undone Move). */
    data class Animate(
        val move: Move,
        override val revision: Long,
        override val target: Facelets,
    ) : PresentationItem

    /** Jump straight to [target] with no animation (Reset). */
    data class Snap(
        override val revision: Long,
        override val target: Facelets,
    ) : PresentationItem
}
