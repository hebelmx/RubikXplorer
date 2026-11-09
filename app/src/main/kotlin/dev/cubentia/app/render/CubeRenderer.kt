package dev.cubentia.app.render

import dev.cubentia.domain.cube.Facelets

/**
 * The rendering port (AD-4). A renderer is a projection of committed state: it draws the queued
 * [PresentationItem]s in order and never mutates the history they came from. Adapters: the 2D
 * [Net2DRenderer] now, a 3D renderer after the OQ-3 spike.
 */
interface CubeRenderer {
    /** Queues [items] behind any not yet drawn. Never drops an item. */
    fun submit(items: List<PresentationItem>)

    /** The facelets currently drawn; equal to the last submitted target once the queue drains. */
    fun renderedFacelets(): Facelets
}
